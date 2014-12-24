JavaNIOProxyService
===================

A Proxy Service which was written to demonstrate the basic concepts of the Java NIO and the Reactor Pattern.

To run the program fill the necessary properties in the config.properties file which resides inside the src/main/resources directory and then run the ListeningIOReactor class using your IDE.

Properties are described below.

secureProxy - whether the proxy is exposed as a secure/HTTPS endpoint.
remoteHost - remote host where the backend service resides
remotePort - remote port associated with the backend service
localPort - proxy service listens for connections on this port number
keystore - file system location of the keystore
keystorepassword - keystore password
truststore - file system location of the client trust store
truststorepassword - client trust store password
secureBackend - whether the backend service is exposed as a secure/HTTPS endpoint.

