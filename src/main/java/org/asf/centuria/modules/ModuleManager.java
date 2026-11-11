package org.asf.centuria.modules;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.zip.ZipInputStream;

import org.asf.cyan.fluid.DynamicClassLoader;
import org.asf.cyan.fluid.bytecode.FluidClassPool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.asf.centuria.modules.dependencies.IMavenRepositoryProvider;
import org.asf.centuria.modules.dependencies.IModuleMavenDependencyProvider;
import org.asf.centuria.modules.eventbus.EventBus;
import org.asf.centuria.modules.eventbus.impl.EventBusImpl;
import org.objectweb.asm.tree.ClassNode;

/**
 * 
 * Centuria Module Manager - loosely based on the Edge module system (ported to
 * the Centuria API)
 * 
 * @author Sky Swimmer
 * 
 */
public class ModuleManager {

	protected static ModuleManager implementation = new ModuleManager();

	/**
	 * Retrieves the Module Manager instance
	 * 
	 * @return ModuleManager instance
	 */
	public static ModuleManager getInstance() {
		return implementation;
	}

	private boolean inited = false;
	private boolean postInited = false;
	private Logger logger;

	private DynamicClassLoader moduleLoader;
	private ArrayList<ICenturiaModule> modules = new ArrayList<ICenturiaModule>();

	/**
	 * Initializes the module manager (internal)
	 */
	public void init() {
		if (inited)
			throw new IllegalStateException("Already initialized");
		inited = true;

		// Init logging
		logger = LogManager.getLogger("MODULEMANAGER");

		// Setup loading
		File modulesDir = new File("modules");
		logger.info("Searching for modules...");
		modulesDir.mkdirs();
		logger.debug("Scanning modules folder...");
		FluidClassPool pool = FluidClassPool.create();
		DynamicClassLoader loader = new DynamicClassLoader();
		moduleLoader = loader;
		EventBusImpl.initClassLoader(loader);

		// Load modules
		findAndLoadModules(pool, modulesDir);

		// Resolve dependencies
		resolveDependencies(pool);

		// Load modules
		logger.info("Dependencies are up-to-date, loading modules...");
		initModules(pool);

		// Clean up
		logger.info("Clearing module pool...");
		try {
			pool.close();
			pool = null;
		} catch (IOException e) {
		}
	}

	private void resolveDependencies(FluidClassPool pool) {
		// Load repos
		logger.info("Searching for dependency repository definitions...");
		ArrayList<IMavenRepositoryProvider> repos = new ArrayList<IMavenRepositoryProvider>();
		for (ClassNode node : pool.getLoadedClasses()) {
			if (nodeExtends(node, pool, IMavenRepositoryProvider.class) && !Modifier.isAbstract(node.access)
					&& !Modifier.isInterface(node.access)) {
				// Found a source
				try {
					logger.debug("Loading repository definition from: " + node.name.replace("/", ".") + "...");
					Class<?> repoCls = moduleLoader.loadClass(node.name.replace("/", "."));
					IMavenRepositoryProvider repoDef = (IMavenRepositoryProvider) repoCls.getConstructor()
							.newInstance();
					if (!repos.stream().anyMatch(t -> t.serverBaseURL().equals(repoDef.serverBaseURL())
							&& t.priority() >= repoDef.priority())) {
						if (repos.stream().anyMatch(t -> t.serverBaseURL().equals(repoDef.serverBaseURL()))) {
							repos.remove(repos.stream().filter(t -> t.serverBaseURL().equals(repoDef.serverBaseURL()))
									.findFirst().get());
						} else {
							logger.info("Added maven repository: " + repoDef.serverBaseURL());
						}
						repos.add(repoDef);
					}
				} catch (Exception e) {
					logger.error("Failed to load repository definition from " + node.name.replace("/", "."), e);
				}
			}
		}
		repos.sort((t1, t2) -> Integer.compare(t1.priority(), t2.priority()));

		// Load dependencies
		ArrayList<IModuleMavenDependencyProvider> deps = new ArrayList<IModuleMavenDependencyProvider>();
		logger.info("Scanning for dependency definitions...");
		for (ClassNode node : pool.getLoadedClasses()) {
			if (nodeExtends(node, pool, IModuleMavenDependencyProvider.class) && !Modifier.isAbstract(node.access)
					&& !Modifier.isInterface(node.access)) {
				// Found a dependency
				try {
					logger.debug("Loading dependency definition from: " + node.name.replace("/", ".") + "...");
					Class<?> depCls = moduleLoader.loadClass(node.name.replace("/", "."));
					IModuleMavenDependencyProvider depDef = (IModuleMavenDependencyProvider) depCls.getConstructor()
							.newInstance();

					// Check if its present
					if (!deps.stream().anyMatch(t -> t.group().equals(depDef.group()) && t.name().equals(depDef.name())
							&& !checkVersionGreaterThan(depDef.version(), t.version()))) {
						// Remove old if needed
						if (deps.stream()
								.anyMatch(t -> t.group().equals(depDef.group()) && t.name().equals(depDef.name())
										&& !checkVersionGreaterThan(depDef.version(), t.version()))) {
							deps.remove(deps.stream()
									.filter(t -> t.group().equals(depDef.group()) && t.name().equals(depDef.name())
											&& !checkVersionGreaterThan(depDef.version(), t.version()))
									.findFirst().get());
						} else
							logger.info("Found dependency: " + depDef.group() + ":" + depDef.name() + ":"
									+ depDef.version());
						deps.add(depDef);
					}
				} catch (Exception e) {
					logger.error("Failed to load repository definition from " + node.name.replace("/", "."), e);
				}
			}
		}

		// Download/update dependencies
		File depsDir = new File("libs");
		depsDir.mkdirs();
		boolean updatedDeps = false;
		logger.info("Verifying dependencies...");
		for (IModuleMavenDependencyProvider depDef : deps) {
			// Log
			logger.debug("Verifying dependency: " + depDef.group() + ":" + depDef.name() + ":" + depDef.version());

			// Load hash if possible
			String oldHash = "";
			String filePath = depDef.name() + (depDef.classifier() != null ? "-" + depDef.classifier() : "")
					+ depDef.extension();
			File file = new File(depsDir, filePath);
			if (file.exists()) {
				// Load hash
				try {
					FileInputStream strm = new FileInputStream(file);
					oldHash = hashFile(strm);
					strm.close();
				} catch (IOException | NoSuchAlgorithmException e) {
					oldHash = "";
				}
			}

			// Read remote hash
			String url = null;
			String remoteHash = null;
			for (IMavenRepositoryProvider repo : repos) {
				try {
					// Build url
					String urlR = repo.serverBaseURL();
					if (!urlR.endsWith("/")) {
						urlR += "/";
					}
					urlR += depDef.group().replace(".", "/");
					urlR += "/";
					urlR += depDef.name();
					urlR += "/";
					urlR += depDef.version();
					urlR += "/";
					urlR += depDef.name();
					urlR += "-";
					urlR += depDef.version();
					if (depDef.classifier() != null) {
						urlR += "-";
						urlR += depDef.classifier();
					}
					urlR += depDef.extension();

					// Download hash
					URL u = new URL(urlR + ".sha1");
					InputStream strm = u.openStream();
					remoteHash = new String(strm.readAllBytes(), "UTF-8").replace("\r", "").replace("\n", "");
					url = urlR;
					strm.close();
					break;
				} catch (Exception e) {
				}
			}

			// Check
			if (url == null) {
				if (oldHash.isEmpty()) {
					logger.fatal("Unable to find a repository that contains dependency " + depDef.group() + ":"
							+ depDef.name() + ":" + depDef.version() + "!");
					System.exit(1);
				} else
					logger.warn("Unable to find a repository that contains dependency " + depDef.group() + ":"
							+ depDef.name() + ":" + depDef.version()
							+ ", unable to check for updates and cannot verify integrity!");
			} else {
				// Check integrity
				if (!oldHash.equals(remoteHash)) {
					// Update
					try {
						logger.info("Updating dependency " + depDef.group() + ":" + depDef.name() + ":"
								+ depDef.version() + "...");
						FileOutputStream fOut = new FileOutputStream(file);
						URL u = new URL(url);
						InputStream strm = u.openStream();
						strm.transferTo(fOut);
						strm.close();
						fOut.close();
						updatedDeps = true;
					} catch (IOException e) {
						// Error
						logger.error("Failed to update dependency " + depDef.group() + ":" + depDef.name() + ":"
								+ depDef.version() + "!", e);
						System.exit(1);
					}
				}
			}
		}
		if (updatedDeps) {
			logger.info("Updated server dependencies, please restart the server.");
			System.exit(237);
		}
	}

	private void findAndLoadModules(FluidClassPool pool, File modulesDir) {
		// Debug modules
		if (System.getProperty("debugMode") != null) {
			// Load all debug modules
			String moduleList = System.getProperty("addCpModules");
			if (moduleList != null) {
				for (String type : moduleList.split(":")) {
					// Load type
					logger.info("Loading debug type: " + type);
					try {
						pool.getClassNode(type);
					} catch (ClassNotFoundException e) {
						logger.error("Failed to load debug module: " + type
								+ ": an error occured while trying to load the class", e);
					}
				}
			}
		}

		// Scan modules
		for (File f : modulesDir.listFiles(t -> t.isFile())) {
			// Attempt to load it
			logger.info("Attempting to load module file: " + f.getName() + "...");
			try {
				// Import
				FileInputStream fIn = new FileInputStream(f);
				ZipInputStream zIn = new ZipInputStream(fIn);
				pool.importArchive(zIn);
				zIn.close();
				fIn.close();
				moduleLoader.addUrl(f.toURI().toURL());
			} catch (Exception e) {
				logger.error("Failed to load module file: " + f.getName() + ": an error occured while reading the file",
						e);
			}
		}

	}

	private void initModules(FluidClassPool pool) {
		// Find modules
		logger.debug("Scanning for module classes...");
		for (ClassNode node : pool.getLoadedClasses()) {
			if (nodeExtends(node, pool, ICenturiaModule.class) && !Modifier.isAbstract(node.access)
					&& !Modifier.isInterface(node.access)) {
				// Found a module
				try {
					// Load it
					logger.debug("Loading module class from: " + node.name.replace("/", ".") + "...");
					Class<?> modCls = moduleLoader.loadClass(node.name.replace("/", "."));
					try {
						ICenturiaModule modInst = (ICenturiaModule) modCls.getConstructor().newInstance();
						logger.info("Loading module " + modInst.id() + " version " + modInst.version() + "...");
						if (modules.stream().anyMatch(t -> t.id().equalsIgnoreCase(modInst.id()))) {
							// Error: duplicate modules
							ICenturiaModule modInst2 = getModule(modInst.id());
							logger.error("Duplicate module ID detected: " + modInst.id() + "\n\n" + modInst.id() + " "
									+ modInst.version() + ": "
									+ new File(modInst.getClass().getProtectionDomain().getCodeSource().getLocation()
											.toURI()).getName()
									+ "\n\n" + modInst2.id() + " " + modInst2.version() + ": "
									+ new File(modInst2.getClass().getProtectionDomain().getCodeSource().getLocation()
											.toURI()).getName());
							System.exit(1);
						}

						// Add to loaded module list and pre-initialize it
						modules.add(modInst);
						try {
							// Pre-init
							modInst.preInit();

							// Attach events
							EventBus.getInstance().addAllEventsFromReceiver(modInst);
						} catch (Exception e) {
							logger.error("Failed to pre-initialize module: " + modInst.id() + ", module source file: "
									+ new File(modCls.getProtectionDomain().getCodeSource().getLocation().toURI())
											.getName(),
									e);
							modules.remove(modInst);
						}
					} catch (Exception e) {
						logger.error(
								"Failed to load module from: " + node.name.replace("/", ".") + ", module source file: "
										+ new File(modCls.getProtectionDomain().getCodeSource().getLocation().toURI())
												.getName(),
								e);
					}
				} catch (Exception e) {
					logger.error("Failed to load module from: " + node.name.replace("/", "."), e);
				}
			}
		}

		// Initialize modules
		logger.info("Initializing modules...");
		for (ICenturiaModule mod : getAllModules()) {
			logger.info("Initializing module: " + mod.id() + "...");
			try {
				mod.init();
			} catch (Exception e) {
				logger.error("Failed to initialize module " + mod.id(), e);
				modules.remove(mod);
			}
		}
	}

	/**
	 * Post-initializes all modules (internal)
	 */
	public void runModulePostInit() {
		if (postInited)
			throw new IllegalStateException("Already post-initialized all modules");
		postInited = true;

		// Post-init modules
		logger.info("Post-initializing modules...");
		for (ICenturiaModule module : getAllModules()) {
			try {
				logger.info("Post-initializing module: " + module.id() + "...");
				module.postInit();
			} catch (Exception e) {
				logger.error("Failed to post-initialize module " + module.id(), e);
			}
		}
	}

	/**
	 * Retrieves modules by ID
	 * 
	 * @param id Module ID
	 * @return IConnectiveModule instance or null
	 */
	public ICenturiaModule getModule(String id) {
		for (ICenturiaModule module : modules) {
			if (module.id().equalsIgnoreCase(id))
				return module;
		}
		return null;
	}

	/**
	 * Retrieves modules by type
	 * 
	 * @param <T>  Module type
	 * @param type Module class
	 * @return Module instance or null
	 */
	@SuppressWarnings("unchecked")
	public <T extends ICenturiaModule> T getModule(Class<T> type) {
		return (T) modules.stream().filter(t -> type.isAssignableFrom(t.getClass())).findFirst().orElseGet(() -> null);
	}

	/**
	 * Retrieves all loaded modules
	 * 
	 * @return Array of IConnectiveModule instancess
	 */
	public ICenturiaModule[] getAllModules() {
		return modules.toArray(t -> new ICenturiaModule[t]);
	}

	private boolean checkVersionGreaterThan(String newversion, String version) {
		newversion = convertVerToVCheckString(newversion.replace("-", ".").replaceAll("[^0-9A-Za-z.]", ""));
		String oldver = convertVerToVCheckString(version.replace("-", ".").replaceAll("[^0-9A-Za-z.]", ""));

		int ind = 0;
		String[] old = oldver.split("\\.");
		for (String vn : newversion.split("\\.")) {
			if (ind < old.length) {
				String vnold = old[ind];
				if (Integer.valueOf(vn) > Integer.valueOf(vnold)) {
					return true;
				} else if (Integer.valueOf(vn) < Integer.valueOf(vnold)) {
					return false;
				}
				ind++;
			} else
				return false;
		}

		return false;
	}

	private String convertVerToVCheckString(String version) {
		char[] ver = version.toCharArray();
		version = "";
		boolean lastWasAlpha = false;
		for (char ch : ver) {
			if (ch == '.') {
				version += ".";
			} else {
				if (Character.isAlphabetic(ch) && !lastWasAlpha && !version.endsWith(".")) {
					version += ".";
					lastWasAlpha = true;
				} else if (lastWasAlpha && !version.endsWith(".")) {
					version += ".";
					lastWasAlpha = false;
				} else {
					version += Integer.toString((int) ch);
				}
			}
		}
		return version;
	}

	private boolean nodeExtends(ClassNode node, FluidClassPool pool, Class<?> target) {
		while (true) {
			// Check node
			if (node.name.equals(target.getTypeName().replace(".", "/")))
				return true;

			// Check interfaces
			if (node.interfaces != null) {
				for (String inter : node.interfaces) {
					try {
						if (nodeExtends(pool.getClassNode(inter), pool, target))
							return true;
					} catch (ClassNotFoundException e) {
					}
				}
			}

			// Check if end was reached
			if (node.superName == null || node.superName.equals("java/lang/Object"))
				break;
			try {
				node = pool.getClassNode(node.superName);
			} catch (ClassNotFoundException e) {
				break;
			}
		}
		return false;
	}

	private String hashFile(InputStream strm) throws IOException, NoSuchAlgorithmException {
		MessageDigest digest = MessageDigest.getInstance("SHA-1");
		byte[] hash = digest.digest(strm.readAllBytes());
		String hashTxt = "";
		for (byte b : hash)
			hashTxt += Integer.toString((b & 0xff) + 0x100, 16).substring(1);
		return hashTxt;
	}

}
