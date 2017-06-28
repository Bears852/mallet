package com.sensepost.mallet.graph;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.w3c.dom.Document;

import com.mxgraph.analysis.StructuralException;
import com.mxgraph.analysis.mxAnalysisGraph;
import com.mxgraph.analysis.mxGraphProperties;
import com.mxgraph.analysis.mxGraphStructure;
import com.mxgraph.io.mxCodec;
import com.mxgraph.model.mxIGraphModel;
import com.mxgraph.util.mxUtils;
import com.mxgraph.util.mxXmlUtils;
import com.mxgraph.view.mxGraph;
import com.sensepost.mallet.ChannelAttributes;
import com.sensepost.mallet.InterceptController;
import com.sensepost.mallet.InterceptHandler;
import com.sensepost.mallet.RelayHandler;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.Mapping;
import io.netty.util.concurrent.GlobalEventExecutor;

public class Graph implements GraphLookup {

	private InterceptController ic;
	private Mapping<? super String, ? extends SslContext> serverCertMapping;
	private SslContext clientContext;

	private boolean direct = true, socks = false;

	private mxGraph graph = new mxGraph();

	private Map<Class<? extends Channel>, EventLoopGroup> bossGroups = new HashMap<>();
	private Map<Class<? extends Channel>, EventLoopGroup> workerGroups = new HashMap<>();

	private ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true);
	private WeakHashMap<ChannelHandler, Object> handlerVertexMap = new WeakHashMap<>();

	public Graph(Mapping<? super String, ? extends SslContext> serverCertMapping, SslContext clientContext) {
		this.serverCertMapping = serverCertMapping;
		this.clientContext = clientContext;
	}

	public void setInterceptController(InterceptController ic) {
		this.ic = ic;
	}

	public mxGraph getGraph() {
		return graph;
	}

	public void loadGraph(File file) throws IOException {
		System.out.println(file.getAbsolutePath());

		Document document = mxXmlUtils.parseXml(mxUtils.readFile(file.getAbsolutePath()));

		mxCodec codec = new mxCodec(document);
		codec.decode(document.getDocumentElement(), graph.getModel());
	}

	private void startServersFromGraph()
			throws StructuralException, ClassNotFoundException, IllegalAccessException, InstantiationException {
		mxAnalysisGraph aGraph = new mxAnalysisGraph();
		aGraph.setGraph(graph);

		mxGraphProperties.setDirected(aGraph.getProperties(), true);

		Object[] sourceVertices = mxGraphStructure.getSourceVertices(aGraph);
		System.out.print("Source vertices of the graph are: [");
		mxIGraphModel model = aGraph.getGraph().getModel();

		for (int i = 0; i < sourceVertices.length; i++) {
			ServerBootstrap b = new ServerBootstrap().handler(new LoggingHandler(LogLevel.INFO))
					.attr(ChannelAttributes.GRAPH, this).childOption(ChannelOption.AUTO_READ, true)
					.childOption(ChannelOption.ALLOW_HALF_CLOSURE, true);
			// parse getValue() for each of sourceVertices to
			// determine what sort of EventLoopGroup we need, etc
			Object serverValue = model.getValue(sourceVertices[i]);
			Class<? extends ServerChannel> channelClass = getServerClass(getClassName(serverValue));
			b.channel(channelClass);
			SocketAddress address = parseSocketAddress(channelClass, serverValue);
			b.childHandler(new GraphChannelInitializer(graph.getOutgoingEdges(sourceVertices[i])[0]));
			b.group(getEventGroup(bossGroups, channelClass, 1), getEventGroup(workerGroups, channelClass, 0));
			channels.add(b.bind(address).syncUninterruptibly().channel());
		}
	}

	private EventLoopGroup getEventGroup(Map<Class<? extends Channel>, EventLoopGroup> cache,
			Class<? extends Channel> channelClass, int threads) {
		EventLoopGroup group = cache.get(channelClass);
		if (group != null)
			return group;
		if (channelClass == NioServerSocketChannel.class) {
			group = new NioEventLoopGroup(threads);
			cache.put(channelClass, group);
			return group;
		}
		throw new IllegalArgumentException(channelClass.toString() + " is not supported yet");
	}

	/**
	 * assumes that o is a String on two lines, first line is the class of the
	 * server, second line is the socketaddress
	 * 
	 * @param channelClass
	 * @param o
	 *            the value Object for the server vertex
	 * @return the SocketAddress specified
	 */
	private SocketAddress parseSocketAddress(Class<? extends Channel> channelClass, Object o) {
		if (NioServerSocketChannel.class.isAssignableFrom(channelClass)) {
			// parse as an InetSocketAddress
			if (o instanceof String) {
				String s = (String) o;
				if (s.indexOf('\n') > -1) {
					s = s.substring(s.indexOf('\n') + 1);
					int c = s.indexOf(':');
					if (c > -1) {
						String address = s.substring(0, c);
						int port = Integer.parseInt(s.substring(c + 1));
						return new InetSocketAddress(address, port);
						// FIXME: check that this is actually a bind-able
						// address?
					}
				}
			}
		}
		throw new IllegalArgumentException("Could not parse the socket address from: '" + o + "'");
	}

	private Class<? extends ServerChannel> getServerClass(String className) throws ClassNotFoundException {
		Class<?> clazz = Class.forName(className);
		if (ServerChannel.class.isAssignableFrom(clazz))
			return (Class<ServerChannel>) clazz;
		throw new IllegalArgumentException(className + " does not implement ServerChannel");
	}

	private ChannelHandler[] getChannelHandlers(Object o)
			throws ClassNotFoundException, IllegalAccessException, InstantiationException {
		List<ChannelHandler> handlers = new ArrayList<ChannelHandler>();
		do {
			if (graph.getModel().isEdge(o))
				o = graph.getModel().getTerminal(o, false);
			Object v = graph.getModel().getValue(o);
			if ("Connect".equals(v))
				break;
			ChannelHandler h = getChannelHandler(v);
			handlers.add(h);
			if ((h instanceof InterceptHandler) || (h instanceof RelayHandler)
					|| (h instanceof IndeterminateChannelHandler)) {
				handlerVertexMap.put(h, o);
				break;
			}
			Object[] outgoing = graph.getOutgoingEdges(o);
			if (outgoing == null || outgoing.length != 1)
				break;
			o = outgoing[0];
		} while (true);
		return handlers.toArray(new ChannelHandler[handlers.size()]);
	}

	private ChannelHandler getChannelHandler(Object o)
			throws ClassNotFoundException, IllegalAccessException, InstantiationException {
		String handlerClass = getClassName(o);
		Class<?> clazz = Class.forName(handlerClass);
		if (InterceptHandler.class.isAssignableFrom(clazz)) {
			return new InterceptHandler(ic);
		} else if (SniHandler.class.isAssignableFrom(clazz)) {
			return new SniHandler(serverCertMapping);
		} else if (SslHandler.class.isAssignableFrom(clazz)) {
			return clientContext.newHandler(PooledByteBufAllocator.DEFAULT);
		} else if (HttpObjectAggregator.class.isAssignableFrom(clazz)) {
			// FIXME need to extract the options from the value object
			return new HttpObjectAggregator(10 * 1024 * 1024);
		} else if (TargetSpecificChannelHandler.class.isAssignableFrom(clazz)) {
			// FIXME need to extract the options from the value object
			return new TargetSpecificChannelHandler();
		} else if (ChannelHandler.class.isAssignableFrom(clazz))
			return (ChannelHandler) clazz.newInstance();
		throw new IllegalArgumentException(handlerClass + " does not implement ChannelHandler!");
	}

	private String getClassName(Object o) {
		if (o instanceof String) {
			String s = (String) o;
			if (s.indexOf('\n') > -1)
				s = s.substring(0, s.indexOf('\n'));
			return s;
		}
		return null;
	}

	@Override
	public void startServers() throws Exception {
		startServersFromGraph();
	}

	@Override
	synchronized public ChannelHandler[] getNextHandlers(ChannelHandler handler, String option) {
		Object vertex = handlerVertexMap.remove(handler);
		Object[] outgoing = graph.getOutgoingEdges(vertex);
		try {
			for (Object edge : outgoing) {
				Object v = graph.getModel().getValue(edge);
				if (option.equals(v))
					return getChannelHandlers(graph.getModel().getTerminal(edge, false));
			}
			throw new NullPointerException("No match found for " + handler.getClass() + ", option " + option);
		} catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
			e.printStackTrace();
			return null;
		}
	}

	@Override
	synchronized public ChannelHandler[] getClientChannelInitializer(ChannelHandler handler) {
		Object vertex = handlerVertexMap.remove(handler);
		try {
			Object[] outgoing = graph.getOutgoingEdges(vertex);
			if (outgoing == null || outgoing.length != 1)
				throw new IllegalStateException("Exactly one outgoing edge allowed!");
			ArrayList<ChannelHandler> handlers = new ArrayList<ChannelHandler>(
					Arrays.asList(getChannelHandlers(outgoing[0])));
			handlers.add(0, handler);
			Collections.reverse(handlers); // FIXME: Decide where to do the
											// reversing, in the graph, or in
											// the caller
			return handlers.toArray(new ChannelHandler[handlers.size()]);
		} catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
			e.printStackTrace();
			return null;
		}
	}

	@Override
	synchronized public ChannelHandler[] getProxyInitializer(ChannelHandler handler, SocketAddress target) {
		if (direct)
			return new ChannelHandler[] { handler };
		else if (socks)
			return new ChannelHandler[] { new Socks5ProxyHandler(new InetSocketAddress("127.0.0.1", 1081)), handler };
		else
			return new ChannelHandler[] { new HttpProxyHandler(new InetSocketAddress("127.0.0.1", 8080)), handler };
	}

	@Override
	public void shutdownServers() throws Exception {
		try {
			channels.close();
		} finally {
			for (EventLoopGroup e : bossGroups.values()) {
				e.shutdownGracefully();
			}
			for (EventLoopGroup e : workerGroups.values()) {
				e.shutdownGracefully();
			}
		}
	}

	private class GraphChannelInitializer extends ChannelInitializer<SocketChannel> {

		private Object serverEdge;

		public GraphChannelInitializer(Object serverEdge) {
			this.serverEdge = serverEdge;
		}

		@Override
		protected void initChannel(SocketChannel ch) throws Exception {
			ChannelHandler[] handlers = getChannelHandlers(serverEdge);
			GraphLookup gl = ch.parent().attr(ChannelAttributes.GRAPH).get();
			ch.attr(ChannelAttributes.GRAPH).set(gl);
			ch.pipeline().addFirst(new ConnectionNumberChannelHandler());
			ch.pipeline().addLast(handlers);
		}

	}
}