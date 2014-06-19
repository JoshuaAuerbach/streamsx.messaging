/* begin_generated_IBM_copyright_prolog                             */
/*                                                                  */
/* This is an automatically generated copyright prolog.             */
/* After initializing,  DO NOT MODIFY OR MOVE                       */
/* **************************************************************** */
/* IBM Confidential                                                 */
/* OCO Source Materials                                             */
/* 5724-Y95                                                         */
/* (C) Copyright IBM Corp.  2013, 2013                              */
/* The source code for this program is not published or otherwise   */
/* divested of its trade secrets, irrespective of what has          */
/* been deposited with the U.S. Copyright Office.                   */
/*                                                                  */
/* end_generated_IBM_copyright_prolog                               */
package com.ibm.streamsx.messaging.jms;

import java.util.List;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import com.ibm.streams.operator.Tuple;
import com.ibm.streams.operator.metrics.Metric;

//This class handles the wbe22 message type 
class WBE22TextMessageHandler extends TextMessageHandler {
	/* begin_generated_IBM_copyright_code */
	public static final String IBM_COPYRIGHT = " Licensed Materials-Property of IBM                              " + //$NON-NLS-1$ 
			" 5724-Y95                                                        "
			+ //$NON-NLS-1$ 
			" (C) Copyright IBM Corp.  2013, 2013    All Rights Reserved.     "
			+ //$NON-NLS-1$ 
			" US Government Users Restricted Rights - Use, duplication or     "
			+ //$NON-NLS-1$ 
			" disclosure restricted by GSA ADP Schedule Contract with         "
			+ //$NON-NLS-1$ 
			" IBM Corp.                                                       "
			+ //$NON-NLS-1$ 
			"                                                                 "; //$NON-NLS-1$ 
	/* end_generated_IBM_copyright_code */

	// the document builder
	private DocumentBuilder documentBuilder;

	// constructor
	public WBE22TextMessageHandler(List<NativeSchema> nativeSchemaObjects,
			String eventName) throws TransformerConfigurationException,
			ParserConfigurationException {
		// call the base class constructor to initialize the native schema
		// attributes and event name

		super(nativeSchemaObjects, eventName);

		documentBuilder = DocumentBuilderFactory.newInstance()
				.newDocumentBuilder();
	}

	// constructor
	public WBE22TextMessageHandler(List<NativeSchema> nativeSchemaObjects,
			String eventName, Metric nTruncatedInserts)
			throws TransformerConfigurationException,
			ParserConfigurationException {
		// call the base class constructor to initialize the native schema
		// attributes,nTruncatedInserts and event name

		super(nativeSchemaObjects, eventName, nTruncatedInserts);
		documentBuilder = DocumentBuilderFactory.newInstance()
				.newDocumentBuilder();
	}

	// For JMSSink operator, convert the incoming tuple to a JMS TextMessage
	public Message convertTupleToMessage(Tuple tuple, Session session)
			throws JMSException, ParserConfigurationException,
			TransformerException {
		// create a new TextMessage
		TextMessage message;
		synchronized (session) {
			message = (TextMessage) session.createTextMessage();
		}
		// create document element
		Document document;
		synchronized (documentBuilder) {
			document = documentBuilder.newDocument();
		}
		// create elements for constructing the xml document
		// with wbe22 mesage format
		Element field;
		Element rootElement = document.createElement("connector"); // creates a
																	// element
		// for root tuple
		rootElement.setAttribute("name", "System S");
		rootElement.setAttribute("version", "2.2");
		Element rootEle1 = document.createElement("connector-object");
		rootEle1.setAttribute("name", eventName);
		rootElement.appendChild(rootEle1);
		String stringdata = new String();
		// variable to specify if any of the attributes in the message is
		// truncated
		boolean isTruncated = false;

		for (NativeSchema currentObject : nativeSchemaObjects) {
			// iterate through the native schema elements
			// extract the name, type and length

			final String name = currentObject.getName();
			final int length = currentObject.getLength();
			field = document.createElement("field"); // create another element
			field.setAttribute("name", name);
			// handle based on data type
			switch (tuple.getStreamSchema().getAttribute(name).getType()
					.getMetaType()) {
			// BLOB is not supported for wbe22 message class
			case RSTRING:
			case USTRING:
				// extract the String
				// get its length
				String rdata = tuple.getString(name);
				int size = rdata.length();
				// If no length was specified in native schema or
				// if the length of the String rdata is less than the length
				// specified in native schema
				if (length == LENGTH_ABSENT_IN_NATIVE_SCHEMA || size <= length) {
					stringdata = rdata;
				}
				// if the length of rdate is greater than the length specified
				// in native schema
				// set the isTruncated to true
				// truncate the String
				else if (size > length) {
					isTruncated = true;
					stringdata = rdata.substring(0, length);
				}
				break;
			// spl types decimal32, decimal64,decimal128, timestamp are mapped
			// to String.
			case TIMESTAMP:
				stringdata = (tuple.getTimestamp(name).getTimeAsSeconds())
						.toString();
				break;

			case DECIMAL32:
			case DECIMAL64:
			case DECIMAL128:

				// for decimal
				stringdata = tuple.getBigDecimal(name).toString();
				break;
			default:
				stringdata = tuple.getString(name);
				break;
			}

			field.appendChild(document.createTextNode(stringdata));
			// append to root element
			rootEle1.appendChild(field);
		}
		// append rootElement to the document
		document.appendChild(rootElement);
		// set the message
		message.setText(createFinalDocument(document));
		// if the isTruncated boolean is set, increment the metric
		// nTruncatedInserts
		if (isTruncated) {
			nTruncatedInserts.incrementValue(1);
		}
		// return the message
		return message;
	}

}