#!/usr/bin/perl -w
#
# Builds a docker container for the project.
#
# Prior to building, an 'sbt dist' must be run.
#
use strict;
use constant DEBUG => 0;
use constant BUILD_PATH => "target/universal";
use constant SOFI_DOCKER_REGISTRY => "build.sofi.com:5000";
use constant BASE_IMAGE => join("/", SOFI_DOCKER_REGISTRY, "sofi-alpine-jre8:v1_8_102");

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

# Use sbt to determine the application and version being built.
my $output = `$SBT_CMD name version`;
print $output."\n";

# Using a perl man's split to get the application and version from SBT
my @lines = split("\n", `$SBT_CMD -Dsbt.log.noformat=true name`);
my $APPLICATION = (split(' ', $lines[-1]))[-1];
@lines = split("\n", `$SBT_CMD -Dsbt.log.noformat=true version`);
my $VERSION = (split(' ', $lines[-1]))[-1];

# Verify that the zip was correctly built.
my $ZIP = "$APPLICATION-$VERSION.zip";
my $ZIP_PATH = BUILD_PATH."/$APPLICATION-$VERSION.zip";
die "Dist $ZIP_PATH was not found" if (! -f $ZIP_PATH);

# Determine the build number, which is the last number in the version if
# building on a Linux box (which is the default).
my $buildNum;
if ($pushBuild && $VERSION =~ /-(\d+)$/) {
    $buildNum = $1;
} else {
    $buildNum = "local";
}

# Correct for SNAPSHOTS
my $BRANCH;
if ($VERSION =~ /SNAPSHOT/) {
    $BRANCH = "develop";
    $VERSION = "develop-$buildNum";
} elsif ($VERSION =~ /release/) {
    $BRANCH = "master";
    $VERSION = "master-$buildNum";
} else {
    # Strip off the version
    $BRANCH = $VERSION;
    $BRANCH =~ s/-$buildNum$//;
}

print "Building '$APPLICATION:$VERSION' ($BRANCH)\n";

# The container id used to identify this build.
my $CONTAINER = "$APPLICATION";
if ($pushBuild) {
    $CONTAINER = SOFI_DOCKER_REGISTRY."/$APPLICATION";
}

# Ok, we've verified the environment, let's refactor the zip to use
# less space in the docker image by doing two things
# 1) Re-write it so that the file structore more closely matches
#    how we want it to be in the docker image. In particular, the
#    zip file contains the version, and we don't need it.
# 2) Create it as a tgz file instead of a zip file, since Docker
#    will auto-unpack it and create a layer directly, vs. copying in
#    a zipfile and then unpacking (creating two layers)
print "Repackaging distribution as a tarfile\n";
my $TAR = repackageZipAsTar($ZIP_PATH, $APPLICATION);
my $TAR_PATH = BUILD_PATH."/$TAR";
die "Re-package as tgz failed (couldn't find $TAR_PATH)" if (! -f $TAR_PATH);

# Config dirs
my $CONF_PATH = "config";
die "Dist $CONF_PATH was not found" if (! -d $CONF_PATH);

# Ok, let's dockerize this build!
print "creating temp docker directory\n";
my $exitCode = dockerize($TAR_PATH, $CONF_PATH, $CONTAINER, $VERSION, $pushBuild);

if ($exitCode == 0) {
    print "SUCCESS\n";
} else {
    print "FAILURE\n";
}
exit $exitCode;

sub repackageZipAsTar {
    my ($path2zip, $application) = @_;

    # Remember our current directory
    my $currDir = `pwd`;
    chomp($currDir);

    # Split up the file and path
    my $zipname = (split("/", $path2zip))[-1];
    my $buildDir = $path2zip;
    $buildDir =~ s#/$zipname##;

    # Determine the zip name
    my $tarname = $application.".tgz";

    my $tempDir = `$MKTEMP_CMD -d`;
    chomp($tempDir);

    # Copy the ZIP file into the tempDir and then repackage it
    eval {
        processExec("cp $path2zip $tempDir");

        # Move into the temp directory, and then create the packaging directories
        chdir($tempDir);
        my $unpack = "unpack";
        my $pack = "sofi";
        mkdir($unpack) or die "Failed to create dir '$unpack'";
        mkdir($pack) or die "Failed to create dir '$pack'";

        # Unpack and move into the desired file structure
        processExec("unzip -q $ZIP -d $unpack") == 0
            or die "Failed to unzip distribution file '$ZIP'";
        processExec("mv $unpack/*/* $pack") == 0
            or die "Failed to move files to new package location";

        # Create the TGZ file
        processExec("tar zcf $tarname $pack") == 0
            or die "Failed to create tarfile '$tarname'";
    };

    # Handle any errors that may have happened
    my $error = $@ if ($@);

    # Move back to the starting directory, and copy the tarfile into the same location as
    # the zipfile was located
    chdir($currDir);
    processExec("cp $tempDir/$tarname $buildDir");

    # Cleanup and return the name of the tarfile
    processExec("rm -rf $tempDir");

    # Abort if we had any failures
    die $error."\n" if (defined $error);

    return $tarname;
}

sub dockerize {
    my ($path2tar, $path2conf, $container, $version, $pushIt) = @_;

    my $tarname = (split("/", $path2tar))[-1];
    my $confDir = (split("/", $path2conf))[-1];


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
        processExec("cp $path2tar $tempDir");
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

        # version.txt
        my $hasVersion = 0;
        my $version = "docker/version.txt";
        if (! -f $version) {
            $version = "version.txt";
        }
        if (-f $version) {
            processExec("cp $version $tempDir");
            $hasVersion = 1;
        }

        # Move into the temp directory, and then create the Dockerfile
        chdir($tempDir);

        # Create the Docker file
        print "Creating Dockerfile\n";
        my $dockerFile = dockerFile($hasEntry, $hasCmd, $hasVersion, $tarname, $confDir);
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
        print "\ndocker tag $CONTAINER:$VERSION $CONTAINER:$BRANCH\n";
        processExec("$DOCKER_CMD tag $CONTAINER:$VERSION $CONTAINER:$BRANCH") == 0
            or die "Failed to tag build";

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
    my ($hasEntry, $hasCmd, $hasVersion, $tarName, $confDir) = @_;

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
    my $version = <<EOF;

# Add and install the application's program files
ADD version.txt /
EOF

    my $dockerFile = $image;
    $dockerFile .= $header;
    $dockerFile .= $entry if ($hasEntry);
    $dockerFile .= $cmd if ($hasCmd);
    $dockerFile .= $version if ($hasVersion);

    $dockerFile .= "\n# Finally, add the distribution\n";
    $dockerFile .= "ADD $tarName /data\n";
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
