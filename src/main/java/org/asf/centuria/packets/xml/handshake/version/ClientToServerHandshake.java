package org.asf.centuria.packets.xml.handshake.version;

import org.asf.centuria.networking.smartfox.SmartfoxClient;
import org.asf.centuria.packets.smartfox.ISmartfoxPacket;
import org.asf.centuria.packets.xml.VersionHandshakePackets;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;

public class ClientToServerHandshake implements ISmartfoxPacket {
	private XmlMapper mapper;
	public String tField;
	public String rField;
	public String actionField;
	public String clientBuild;

	public ClientToServerHandshake(XmlMapper mapper) {
		this.mapper = mapper;
	}

	@Override
	public ISmartfoxPacket instantiate() {
		return new ClientToServerHandshake(mapper);
	}

	@Override
	public boolean canParse(String content) {
		try {
			mapper.readValue(content, VersionHandshakePackets.Request.msg.class);
			return true;
		} catch (Exception e) {
		}
		return false;
	}

	@Override
	public boolean parse(String content) {
		try {
			VersionHandshakePackets.Request.msg data = mapper.readValue(content,
					VersionHandshakePackets.Request.msg.class);
			tField = data.t;
			rField = data.body.r;
			actionField = data.body.action;
			clientBuild = data.body.ver.v;
			return true;
		} catch (Exception e) {
		}

		return false;
	}

	@Override
	public String build() {
		return null;
	}

	@Override
	public boolean handle(SmartfoxClient client) {
		return false;
	}
}
