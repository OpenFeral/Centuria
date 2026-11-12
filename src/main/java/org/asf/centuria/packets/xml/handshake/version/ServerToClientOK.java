package org.asf.centuria.packets.xml.handshake.version;

import org.asf.centuria.packets.smartfox.ISmartfoxPacket;
import org.asf.centuria.networking.smartfox.SmartfoxClient;
import org.asf.centuria.packets.xml.VersionHandshakePackets;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

public class ServerToClientOK implements ISmartfoxPacket {
	private XmlMapper mapper;

	public ServerToClientOK(XmlMapper mapper) {
		this.mapper = mapper;
	}

	@Override
	public ISmartfoxPacket instantiate() {
		return new ServerToClientOK(mapper);
	}

	@Override
	public boolean canParse(String content) {
		return false;
	}

	@Override
	public boolean parse(String content) {
		return false;
	}

	@Override
	public String build() {
		try {
			VersionHandshakePackets.Response.msg m = new VersionHandshakePackets.Response.msg();
			m.body.action = "apiOK";
			m.body.r = "0";
			return mapper.writeValueAsString(m);
		} catch (JacksonException e) {
			return null;
		}
	}

	@Override
	public boolean handle(SmartfoxClient client) {
		return false;
	}

}
