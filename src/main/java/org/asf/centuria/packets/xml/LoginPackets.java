package org.asf.centuria.packets.xml;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlCData;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

public class LoginPackets {

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

			public login login;
		}

		public static class login {
			@JacksonXmlProperty(isAttribute = true)
			public String z;
			
			@JacksonXmlCData
			public String nick;

			@JacksonXmlCData
			public String pword;
		}
	}

}
