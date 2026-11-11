package org.asf.centuria.packets.xml.handshake.auth;

import java.io.IOException;

import org.asf.centuria.networking.smartfox.SmartfoxClient;
import org.asf.centuria.packets.smartfox.ISmartfoxPacket;
import org.asf.centuria.packets.xml.LoginPackets;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;

public class ClientToServerAuthPacket implements ISmartfoxPacket {
	private XmlMapper mapper;
	public String tField;
	public String rField;
	public String zField;
	public String actionField;
	public String nick;
	public String pword;

	public ClientToServerAuthPacket(XmlMapper mapper) {
		this.mapper = mapper;
	}

	@Override
	public ISmartfoxPacket instantiate() {
		return new ClientToServerAuthPacket(mapper);
	}

	@Override
	public boolean canParse(String content) {
		try {
			mapper.readValue(content, LoginPackets.Request.msg.class);
			return true;
		} catch (Exception e) {
		}
		return false;
	}

	@Override
	public boolean parse(String content) {
		try {
			LoginPackets.Request.msg msg = mapper.readValue(content, LoginPackets.Request.msg.class);
			tField = msg.t;
			rField = msg.body.r;
			actionField = msg.body.action;
			zField = msg.body.login.z;
			nick = msg.body.login.nick;
			pword = msg.body.login.pword;
			return true;
		} catch (Exception e) {

		}
		return false;
	}

	@Override
	public String build() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean handle(SmartfoxClient client) throws IOException {
		// TODO Auto-generated method stub
		return false;
	}

}
