FROM build.sofi.com:5000/sofi-alpine-jre8:v1_8_102

# Add and install the application's program files
RUN mkdir -p /data/sofi/bin
ADD FIXimulator_0.41.jar /data/sofi/bin
ADD lib /data/sofi/bin/lib
ADD config /data/sofi/bin/config

# Allows for initialization or for alternate start commands
ADD docker-entrypoint.sh /
ENTRYPOINT ["/docker-entrypoint.sh"]

# Default startup for the application.
ADD start /data/sofi/bin/
CMD ["/data/sofi/bin/start"]

#
# The latter lines are not necessary, but are provided to document
# the external environment setup by our deployments.
#

# Document the Volumes that get mounted in the container
VOLUME ["/shared"]
VOLUME ["/data/newrelic"]
VOLUME ["/data/sofi/logs"]
VOLUME ["/config"]

# Play applications run on Port 9000
EXPOSE 9000
