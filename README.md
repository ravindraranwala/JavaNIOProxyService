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
secureBackend - whether the backend service is exposed as a secure/HTTPS endpoint or NOT.


How to send the request from the client
=========================================
First, send your request to the proxy service and append the relative URL of the backend service to that. For an example,

curl -k -v -d @currencyReq.xml -H "Content-Type: text/xml; charset=utf-8" -H 'Host: www.webservicex.net'  "SOAPAction:urn:ConversionRate"  http://localhost:8585/currencyconvertor.asmx

where currencyconvertor.asmx is the relative url of the backend service 'http://www.webservicex.net/currencyconvertor.asmx' which we need to invoke. 

Also makesure to give all the HTTP headers as specified in the above sample request. In this case the backend service runs on the 'www.webservicex.net' host machine against the 80 port which needs to be provided to the 'remoteHost' and 'remotePort' property values in the config.properties file. HTTP Host header is a must in this case, and otherwise you may end up with HTTP 404 requested resource not found error.


The backend service URL which we need to invoke here is : http://www.webservicex.net/currencyconvertor.asmx

