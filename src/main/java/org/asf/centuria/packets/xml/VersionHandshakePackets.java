package org.asf.centuria.packets.xml;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

public class VersionHandshakePackets {

	public static class Response {
		public static class msg {
			@JacksonXmlProperty(isAttribute = true)
			public String t = "sys";

			public body body = new body();
		}

		public static class body {
			@JacksonXmlProperty(isAttribute = true)
			public String action;

			@JacksonXmlProperty(isAttribute = true)
			public String r;
		}
	}

	public static class Request {
		public static class msg {
			@JacksonXmlProperty(isAttribute = true)
			public String t = "sys";

			public body body = new body();
		}

		public static class body {
			@JacksonXmlProperty(isAttribute = true)
			public String action;

			@JacksonXmlProperty(isAttribute = true)
			public String r;

			public ver ver;
		}

		public static class ver {
			@JacksonXmlProperty(isAttribute = true)
			public String v;
		}
	}

}
