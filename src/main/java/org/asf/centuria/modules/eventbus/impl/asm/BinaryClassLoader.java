package org.asf.centuria.modules.eventbus.impl.asm;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.util.HashMap;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.security.CodeSource;

public class BinaryClassLoader extends URLClassLoader {

	private ClassLoader parent;
	private HashMap<String, Class<?>> loaded = new HashMap<String, Class<?>>();

	public BinaryClassLoader(ClassLoader parent) {
		super(new URL[0], parent);
		this.parent = parent;
	}

	@SuppressWarnings("unchecked")
	public <T> Class<T> loadClassBinary(String name, byte[] input, Class<T> castType, URL source) {
		Class<?> type = defineClass(name, ByteBuffer.wrap(input), new CodeSource(source, (Certificate[]) null));
		loaded.put(type.getTypeName(), type);
		return (Class<T>) type;
	}

	@Override
	public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		if (loaded.containsKey(name))
			return loaded.get(name);
		Class<?> cls = parent.loadClass(name);
		loaded.put(cls.getTypeName(), cls);
		return cls;
	}

	public <T> Class<T> loadClassBinary(ClassNode node, Class<T> castType) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		byte[] bytecode = writer.toByteArray();
		try {
			return loadClassBinary(node.name.replace("/", "."), bytecode, castType,
					new URL(castType.getProtectionDomain().getCodeSource().getLocation() + "/synth/"
							+ System.currentTimeMillis()));
		} catch (MalformedURLException e) {
			throw new RuntimeException(e);
		}
	}
}