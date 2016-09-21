#!/usr/bin/perl -w
#
# Builds a docker container for the project.
#
# Prior to building, an 'sbt dist' must be run.
#
use strict;
use constant DEBUG => 0;
use constant BUILD_PATH => "dist";
use constant SOFI_DOCKER_REGISTRY => "build.sofi.com:5000";
use constant BASE_IMAGE => join("/", SOFI_DOCKER_REGISTRY, "sofi-alpine-jre8:v1_8_102");

# Hard-coded
my $APPLICATION = "sofi-fiximulator";
my $VERSION = "headless";

# Build tools
my $DOCKER_CMD='sudo docker';
my $MKTEMP_CMD="mktemp --tmpdir=/data/tmp";
my $SBT_CMD="/home/bamboo/bin/sbt-0.13.0";
my $pushBuild = 1;
 
# If we're on a Mac, change the way we do things
my $os=qx(uname);
if ($os =~ /Darwin/) {
    $DOCKER_CMD="docker";
    $MKTEMP_CMD="mktemp -t sofi";
    $SBT_CMD="sbt";
    $pushBuild = 0;
}

if (DEBUG) {
    print "DOCKER=$DOCKER_CMD\n";
    print "MKTEMP=$MKTEMP_CMD\n";
    print "SBT=$SBT_CMD\n";
    print "pushBuild=$pushBuild\n";
}

# Verify that the jar was correctly built.
my $JAR = "FIXimulator_0.41.jar";
my $JAR_PATH = join('/', BUILD_PATH, $JAR);
die "Dist $JAR_PATH was not found" if (! -f $JAR_PATH);

# Lib and conf dirs
my $LIB = "lib";
my $LIB_PATH = join('/', BUILD_PATH, $LIB);
die "Dist $LIB_PATH was not found" if (! -d $LIB_PATH);
my $CONF_PATH = "config";
die "Dist $CONF_PATH was not found" if (! -d $CONF_PATH);

# The container id used to identify this build.
my $CONTAINER = "$APPLICATION";
if ($pushBuild) {
    $CONTAINER = SOFI_DOCKER_REGISTRY."/$APPLICATION";
}

# Ok, let's dockerize this build!
print "creating temp docker directory\n";
my $exitCode = dockerize($JAR_PATH, $LIB_PATH, $CONF_PATH, $CONTAINER, $pushBuild);

if ($exitCode == 0) {
    print "SUCCESS\n";
} else {
    print "FAILURE\n";
}
exit $exitCode;

sub dockerize {
    my ($path2jar, $path2lib, $path2conf, $container, $pushIt) = @_;

    my $jarname = (split("/", $path2jar))[-1];
    my $libname = (split("/", $path2lib))[-1];
    my $confname = (split("/", $path2conf))[-1];

    # Remember our current directory
    my $currDir = `pwd`;
    chomp($currDir);

    my $tempDir = `$MKTEMP_CMD -d`;
    chomp($tempDir);

    eval {
        #
        # Copy all the files for building the docker image
        #

        # The distribution itself
        processExec("cp $path2jar $tempDir");
        processExec("cp -r $path2lib $tempDir");
        processExec("cp -r $path2conf $tempDir");

        # Entrypoint?
        my $hasEntry = 0;
        my $entryPoint = "docker/docker-entrypoint.sh";
        if (-f $entryPoint) {
            processExec("cp $entryPoint $tempDir");
            $hasEntry = 1;
        }

        # startCmd
        my $hasCmd = 0;
        my $startCmd = "docker/start";
        if (-f $startCmd) {
            processExec("cp $startCmd $tempDir");
            $hasCmd = 1;
        }

        # Move into the temp directory, and then create the Dockerfile
        chdir($tempDir);

        # Create the Docker file
        print "Creating Dockerfile\n";
        my $dockerFile = dockerFile($hasEntry, $hasCmd, $jarname, $libname, $confname);
        open(DOCKERFILE, "> Dockerfile") or die "Can't create Dockerfile";
        print DOCKERFILE $dockerFile."\n";
        close(DOCKERFILE);

        # Build the docker image
        print "running docker build of $CONTAINER:$VERSION\n";
        processExec("$DOCKER_CMD build -t $CONTAINER:$VERSION .") == 0
            or die "Failed to build image";

        # Get Image ID of this build
        my $imageId;
        open(IMAGES, "$DOCKER_CMD images --no-trunc 2>&1 |") or die "Can't run $DOCKER_CMD";
        while (<IMAGES>) {
            if (/$CONTAINER/ && /$VERSION/) {
                $imageId = (split(' ', $_))[2];
            }
        }
        die "Failed to find the image ID for build\n" if (!defined $imageId);

        # Make this the 'latest' version for the branch
#        print "\ndocker tag $CONTAINER:$VERSION $CONTAINER:$BRANCH\n";
#        processExec("$DOCKER_CMD tag $CONTAINER:$VERSION $CONTAINER:$BRANCH") == 0
#            or die "Failed to tag build";

        # Push to the build server and remove local images for any non-local builds.
        if ($pushBuild) {
            print "docker push $CONTAINER\n";
            processExec("$DOCKER_CMD push $CONTAINER") == 0
                or die "Failed to push image to registry";

            # Debug output for the logs
            my $output = `$DOCKER_CMD images`;
            print $output."\n";

            # Finally, remove the image from the build server since it's been pushed to the
            # registry
            processExec("$DOCKER_CMD rmi $imageId");
        }

        print "Cleaning up failed and unused builds\n";

        # This removes any instance from a failed build (which crashes) that
        # would otherwise not allow us to remove the local image.
        processExec("$DOCKER_CMD rm -v `$DOCKER_CMD ps -a -q` > /dev/null 2>&1");

        # Remove dangling images (un-connected to any tagged version)
        processExec("$DOCKER_CMD images --filter \"dangling=true\" -q > /dev/null 2>&1");
    };

    # Handle any errors that may have happened building the image
    my $exitVal = 0;
    if ($@) {
        warn $@."\n";
        $exitVal = -1;
    }

    # Move back to the starting directory, and cleanup
    chdir($currDir);
    processExec("rm -rf $tempDir");

    return $exitVal;
}

sub dockerFile {
    my ($hasEntry, $hasCmd, $jarName, $libDir, $confDir) = @_;

    my $image = "FROM ".BASE_IMAGE."\n\n";
    my $header = <<EOF;
# Document the Volumes that get mounted in the container
VOLUME ["/shared"]
VOLUME ["/data/newrelic"]
VOLUME ["/data/sofi/logs"]
VOLUME ["/config"]

# Play applications run on Port 9000
EXPOSE 9000
EOF
    my $entry = <<EOF;

# Allows for initialization or for alternate start commands
ADD docker-entrypoint.sh /
ENTRYPOINT ["/docker-entrypoint.sh"]
EOF
    my $cmd = <<EOF;

# Default startup for the application.
ADD start /data/sofi/bin/
CMD ["/data/sofi/bin/start"]
EOF

    my $dockerFile = $image;
    $dockerFile .= $header;
    $dockerFile .= $entry if ($hasEntry);
    $dockerFile .= $cmd if ($hasCmd);

    $dockerFile .= "\n# Finally, add the distribution\n";
    $dockerFile .= "RUN mkdir -p /data/sofi/bin\n";
    $dockerFile .= "ADD $jarName /data/sofi/bin\n";
    $dockerFile .= "ADD $libDir /data/sofi/bin/$libDir\n";
    $dockerFile .= "ADD $confDir /data/sofi/bin/$confDir\n";

    return $dockerFile;
}

sub processExec {
    my (@args) = @_;

    # The name of the program to execute *must* be the first argument.
    my $program = shift(@args);

    my $cmd = join(" ", $program, @args);
    print "\t$cmd\n" if (DEBUG);
    my $exitVal = system($cmd);
    return $exitVal >> 8;
}
