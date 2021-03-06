# Mallet, a framework for creating proxies

Mallet is a tool for creating proxies for arbitrary protocols, along similar lines to the familiar intercepting web proxies, just more generic.

It is built upon the Netty framework, and relies heavily on the Netty pipeline concept, which allows the graphical assembly of graphs of handlers. (See [Screenshots](#screenshots) below for an example.) In the Netty world, handler instances provide frame delimitation (i.e. where does a message start and end), protocol decoding and encoding (converting a stream of bytes into Java objects, and back again, or converting a stream of bytes into a different stream of bytes - think compression and decompression), and higher level logic (actually doing something with those objects).

By following the careful separation of Codecs from Handlers that actually manipulate the messages, Mallet can benefit from the large library of existing Codecs, and avoid reimplementation of many protocols. The final piece of the puzzle is provided by a Handler that copies messages received on one pipeline to another pipeline, proxying those messages on to their final destination.

Of course, while the messages are within Mallet, they can easily be tampered with, either with custom Handlers written in Java or a JSR-223 compliant scripting language, or manually, using one of the provided editors.

You can get an idea of the available codecs by looking at the Netty source at [GitHub](https://github.com/netty/netty/), under the ```codec*``` directories. Or just by googling for ```netty``` and the protocol you are interested in. Many interesting protocol implementations have been developed outside of the core Netty project, but should still work well with Mallet.

# Who might use Mallet?

Mallet is aimed at people working with networked applications that are not based on HTTP communications. Examples might be Internet of Things, which could use MQTT, COAP, etc, ATM's and Point of Sale devices which might use ISO8583, or, to be honest, any other protocol.

In fact, Mallet may even be useful for HTTP-based applications, which use additional protocols within HTTP. For example, Google Protobuf over WebSockets, which are not well supported by existing HTTP proxies such as Burp, Zap, etc, or gRPC over HTTP2.

Mallet is not necessarily only for security reviews. Because Mallet is built on top of the [Netty Framework](https://netty.io), once your pipeline has been prototyped using Mallet, you can migrate your code into a plain Netty application with very little effort.

# Screenshots

This is an example of a simple SOCKS proxy, which can be used as the first step when understanding the network traffic you are seeing.

![Mallet New Diagram](img/Mallet_New_Diagram.png?raw=true "New Diagram")

Once you have an idea of what the traffic actually looks like, you can start adding appropriate [ChannelHandler](https://netty.io/4.1/api/io/netty/channel/ChannelHandler.html) classes along the pipeline.

# Building Mallet

Mallet makes use of Maven, so compiling the code is a matter of

```
mvn package
```

To run it:

```
cd target/
java -jar mallet-1.0-SNAPSHOT-spring-boot.jar
```

There are a few sample graphs provided in the ```examples/``` directory. The JSON graphs expect a JSON client to connect to Mallet on localhost:9998/tcp, with the real server at localhost:9999/tcp. Only the last JSON graph (json5.mxe) makes any assumptions about the structure of the JSON messages being passed, so they should be applicable to any app that sends JSON messages.

The demo.mxe shows a complex graph, with two pipelines, both TCP and UDP. The TCP pipeline is built to support HTTP and HTTPS on ports 80 and 443 respectively, as well as WebSockets, while relaying any other traffic directly to its destination. The UDP pipeline is built to process DNS requests on localhost:1053/udp, replace queries for google.com with queries for www.sensepost.com, and forward the requests on to Google DNS servers.

# Feedback and contributions

Feedback and contributions are welcome. Please create issues where appropriate, or contact the author on Twitter @RoganDawes.
