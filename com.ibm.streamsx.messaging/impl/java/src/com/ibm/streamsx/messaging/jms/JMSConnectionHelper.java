/*******************************************************************************
 * Copyright (C) 2013, 2014, International Business Machines Corporation
 * All Rights Reserved
 *******************************************************************************/
package com.ibm.streamsx.messaging.jms;

import com.ibm.streams.operator.metrics.Metric;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.DeliveryMode;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import com.ibm.streams.operator.logging.LogLevel;

/* This class contains all the connection related information, creating maintaining and closing a connection to the JMSProvider
 * Sending and Receiving JMS messages
 */
class JMSConnectionHelper {

	// variables required to create connection
	// connection factory
	private ConnectionFactory connFactory = null;
	// destination
	private Destination dest = null;
	// jndi context
	private Context jndiContext = null;
	// connection
	private Connection connect = null;
	// JMS message producer
	private MessageProducer producer = null;
	// JMS message consumer
	private MessageConsumer consumer = null;
	// JMS session
	private Session session = null;
	// The reconnection Policy specified by the user
	// defaults to bounderRetry.
	private final ReconnectionPolicies reconnectionPolicy;
	// ReconnectionBound
	private final int reconnectionBound;
	// Period
	private final double period;
	// Is is a producer(JMSSink) or Consumer(JMSSource)
	// set to true for JMSSink,false for JMSSource
	private final boolean isProducer;
	// the delivery mode
	private final String deliveryMode;
	// the metric which specifies the number of reconnection attempts
	// made in case of initial or transient connection failures.
	private Metric nReconnectionAttempts;
	// Metric to indicate the number of failed inserts to the JMS Provider by
	// JMSSink
	private Metric nFailedInserts;
	// userPrincipal and userCredential will be initialized by 
	// createAdministeredObjects and used for connection
	private String userPrincipal = null;
	private String userCredential = null;
	// SIB thin client support
	private boolean thinClient = false;
	private boolean isTopic = false;
	private static final String JmsFactoryFactory = "com.ibm.websphere.sib.api.jms.JmsFactoryFactory";
	private static final String JmsConnectionFactory = "com.ibm.websphere.sib.api.jms.JmsConnectionFactory";

	// procedure to detrmine if there exists a valid connection or not
	private boolean isConnectValid() {
		if (connect != null)
			return true;
		return false;
	}

	// getter for consumer
	private synchronized MessageConsumer getConsumer() {
		return consumer;
	}

	// setter for consumer
	private synchronized void setConsumer(MessageConsumer consumer) {
		this.consumer = consumer;
	}

	// getter for producer
	private synchronized MessageProducer getProducer() {
		return producer;
	}

	// setter for producer
	private synchronized void setProducer(MessageProducer producer) {
		this.producer = producer;
	}

	// getter for session
	synchronized Session getSession() {
		return session;
	}

	// setter for session, synchnized to avoid concurrent access to session
	// object
	private synchronized void setSession(Session session) {
		this.session = session;
	}

	// setter for connect
	// connect is thread safe.Hence not synchronizing.
	private void setConnect(Connection connect) {
		this.connect = connect;
	}

	// getter for connect
	private Connection getConnect() {
		return connect;
	}

	// logger to get error messages
	private Logger logger;

	// This constructor sets the parameters required to create a connection for
	// JMSSource
	JMSConnectionHelper(ReconnectionPolicies reconnectionPolicy,
			int reconnectionBound, double period, boolean isProducer,
			String deliveryMode, Metric nReconnectionAttempts, Logger logger) {
		this.reconnectionPolicy = reconnectionPolicy;
		this.reconnectionBound = reconnectionBound;
		this.period = period;
		this.isProducer = isProducer;
		this.deliveryMode = deliveryMode;
		this.logger = logger;
		this.nReconnectionAttempts = nReconnectionAttempts;
	}

	// This constructor sets the parameters required to create a connection for
	// JMSSink
	JMSConnectionHelper(ReconnectionPolicies reconnectionPolicy,
			int reconnectionBound, double period, boolean isProducer,
			String deliveryMode, Metric nReconnectionAttempts,
			Metric nFailedInserts, Logger logger) {
		this(reconnectionPolicy, reconnectionBound, period, isProducer,
				deliveryMode, nReconnectionAttempts, logger);
		this.nFailedInserts = nFailedInserts;

	}

	// Method to create the initial connection
	public void createInitialConnection() throws ConnectionException,
			InterruptedException {
		createConnection();
		return;
	}
	
	// Method to set the SIB thin Client boolean properties
	public void setThinClientExtras(boolean thinClient, boolean isTopic) {
		this.thinClient = thinClient;
		this.isTopic = isTopic;
	}

	// this subroutine creates the initial jndi context by taking the mandatory
	// and optional parameters

	public void createAdministeredObjects(String initialContextFactory,
			String providerURL, String userPrincipal, String userCredential,
			String connectionFactory, String destination)
			throws Exception { 

		this.userPrincipal = userPrincipal;
		this.userCredential = userCredential;
		
		if (thinClient) {
			// The initial context factory has a special value to indicate the SIB thinclient
			// The providerURL is overloaded in this case to provide the bus name
			// The connection factory name is overloaded in this case to provide the provider endpoints
			createObjectsForThinClient(providerURL, connectionFactory, destination);
			return;
		}
		
		// Create a JNDI API InitialContext object if none exists
		// create a properties object and add all the mandatory and optional
		// parameter
		// required to create the jndi context as specified in connection
		// document
		Properties props = new Properties();
		props.put(Context.INITIAL_CONTEXT_FACTORY, initialContextFactory);
		props.put(Context.PROVIDER_URL, providerURL);

		// Add the optional elements

		if (userPrincipal != null && userCredential != null) {
			props.put(Context.SECURITY_PRINCIPAL, userPrincipal);
			props.put(Context.SECURITY_CREDENTIALS, userCredential);
		}

		// create the jndi context

		jndiContext = new InitialContext(props);

		// Look up connection factory and destination. If either does not exist,
		// exit, throws a NamingException if lookup fails

		connFactory = (ConnectionFactory) jndiContext.lookup(connectionFactory);
		dest = (Destination) jndiContext.lookup(destination);

		return;
	}

	// Initialization for SIB thin client profile
	// Some operations are done reflectively because the thin client jar is not expected to be present at build time.
	// If they are not present at runtime that will be a runtime error.  Equivalent non-reflective code:
	// 	JmsFactoryFactory factory = JmsFactoryFactory.getInstance();
	//	JmsConnectionFactory  cf = factory.createConnectionFactory();
	//	cf.setBusName(busName);
	//	cf.setProviderEndpoints(providerEndpoints);
	//	connFactory = cf;
	//	dest = isTopic ? factory.createTopic(destination): factory.createQueue(destination);

	private void createObjectsForThinClient(String busName, String providerEndpoints, String destination) 
			throws ClassNotFoundException, NoSuchMethodException, SecurityException,
			IllegalAccessException, InstantiationException, IllegalArgumentException, InvocationTargetException {
		/* Get the necessary meta-objects */
		Class<?> factoryClass = Class.forName(JmsFactoryFactory);
		Method getInstance = factoryClass.getDeclaredMethod("getInstance");
		getInstance.setAccessible(true);
		Method createConnectionFactory = factoryClass.getDeclaredMethod("createConnectionFactory");
		createConnectionFactory.setAccessible(true);
		String createDestinationMethod = isTopic ? "createTopic" : "createQueue";
		Method createDestination = factoryClass.getDeclaredMethod(createDestinationMethod, String.class);
		createDestination.setAccessible(true);
		Class<?> connectorClass = Class.forName(JmsConnectionFactory);
		Method setBusName = connectorClass.getDeclaredMethod("setBusName", String.class);
		setBusName.setAccessible(true);
		Method setProviderEndpoints = connectorClass.getDeclaredMethod("setProviderEndpoints", String.class);
		setProviderEndpoints.setAccessible(true);

		/* Do the initialization */
		Object factory = getInstance.invoke(null);
		Object cf = createConnectionFactory.invoke(factory);
		setBusName.invoke(cf, busName);
		setProviderEndpoints.invoke(cf, providerEndpoints);
		connFactory = (ConnectionFactory) cf;
		dest = (Destination) createDestination.invoke(factory, destination);
	}

	// this subroutine creates the connection, it always verifies if we have a
	// successfull existing connection before attempting to create one.
	private synchronized void createConnection() throws ConnectionException,
			InterruptedException {
		int nConnectionAttempts = 0;
		// Check if connection exists or not.
		if (!isConnectValid()) {

			// Delay in miliseconds as specified in period parameter
			final long delay = TimeUnit.MILLISECONDS.convert((long) period,
					TimeUnit.SECONDS);

			while (!Thread.interrupted()) {
				// make a call to connect subroutine to create a connection
				// for each unsuccesfull attempt increment the
				// nConnectionAttempts
				try {
					nConnectionAttempts++;
					if (connect(isProducer)) {
						// got a successfull connection,
						// come out of while loop.
						break;
					}

				} catch (JMSException e) {
					logger.log(LogLevel.ERROR, "RECONNECTION_EXCEPTION",
							new Object[] { e.toString() });
					// Get the reconnectionPolicy
					// Apply reconnection policies if the connection was
					// unsuccessful

					if (reconnectionPolicy == ReconnectionPolicies.NoRetry) {
						// Check if ReconnectionPolicy is noRetry, then abort
						throw new ConnectionException(
								"Connection to JMS failed. Did not try to reconnect as the policy is noReconnect");
					}

					// Check if ReconnectionPolicy is BoundedRetry, then try
					// once in
					// interval defined by period till the reconnectionBound
					// If no ReconnectionPolicy is mentioned then also we have a
					// default value of reconnectionBound and period

					else if (reconnectionPolicy == ReconnectionPolicies.BoundedRetry
							&& nConnectionAttempts == reconnectionBound) {
						// Bounded number of retries has exceeded.
						throw new ConnectionException(
								"Connection to JMS failed. Bounded number of tries has exceeded");
					}
					// sleep for delay interval
					Thread.sleep(delay);
					// Incremet the metric nReconnectionAttempts
					nReconnectionAttempts.incrementValue(1);
				}

			}

		}
	}

	// this subroutine creates the connection, producer and consumer, throws a
	// JMSException if it fails
	private boolean connect(boolean isProducer) throws JMSException {

		// Create connection.
		if (userPrincipal != null && !userPrincipal.isEmpty() && 
				userCredential != null && !userCredential.isEmpty() )
			setConnect(connFactory.createConnection(userPrincipal, userCredential));
		else
			setConnect(connFactory.createConnection());
		
		// Create session from connection; false means
		// session is not transacted.
		setSession(getConnect().createSession(false, Session.AUTO_ACKNOWLEDGE));

		if (isProducer == true) {
			// Its JMSSink, So we will create a producer
			setProducer(getSession().createProducer(dest));

			// set the delivery mode if it is specified
			// default is non-persistent
			if (deliveryMode == null) {
				getProducer().setDeliveryMode(DeliveryMode.NON_PERSISTENT);
			} else {
				if (deliveryMode.trim().toLowerCase().equals("non_persistent")) {
					getProducer().setDeliveryMode(DeliveryMode.NON_PERSISTENT);
				}

				if (deliveryMode.trim().toLowerCase().equals("persistent")) {
					getProducer().setDeliveryMode(DeliveryMode.PERSISTENT);
				}
			}

		} else {
			// Its JMSSource, So we will create a consumer
			setConsumer(getSession().createConsumer(dest));
			// start the connection
			getConnect().start();
		}
		// create connection is successful, return true
		return true;
	}

	// subroutine which on receiving a message, send it to the
	// destination,update the metric
	// nFailedInserts if the send fails

	boolean sendMessage(Message message) throws ConnectionException,
			InterruptedException, JMSException {

		try {
			// try to send the message
			synchronized (getSession()) {
				getProducer().send(message);
			}
		}

		catch (JMSException e) {
			// error has occurred, we need to update the nFailedInserts metric
			setConnect(null);
			logger.log(LogLevel.WARN, "ERROR_DURING_SEND",
					new Object[] { e.toString() });
			logger.log(LogLevel.INFO, "ATTEMPT_TO_RECONNECT");
			nFailedInserts.incrementValue(1);
			// Recreate the connection objects if we don't have any (this
			// could happen after a connection failure)
			createConnection();
			// retry sending the mesage
			synchronized (getSession()) {
				getProducer().send(message);
			}
			return false;
		}
		return true;
	}

	// this subroutine receives messages from a message consumer
	Message receiveMessage() throws ConnectionException, InterruptedException,
			JMSException {
		try {
			// try to receive a message
			synchronized (getSession()) {
				return (getConsumer().receive());
			}

		}

		catch (JMSException e) {
			// If the JMSSource operator was interrupted in middle
			if (e.toString().contains("java.lang.InterruptedException")) {
				throw new java.lang.InterruptedException();
			}
			// Recreate the connection objects if we don't have any (this
			// could happen after a connection failure)
			setConnect(null);
			logger.log(LogLevel.WARN, "ERROR_DURING_RECEIVE",
					new Object[] { e.toString() });
			logger.log(LogLevel.INFO, "ATTEMPT_TO_RECONNECT");
			createConnection();
			// retry to receive
			synchronized (getSession()) {
				return (getConsumer().receive());
			}
		}
	}

	// close the connection
	public void closeConnection() throws JMSException {

		if (getSession() != null) {
			getSession().close();
		}
		if (getConnect() != null) {
			getConnect().close();
		}
	}
}
