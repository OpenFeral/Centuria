package org.asf.centuria.modules.eventbus.impl;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.asf.centuria.modules.eventbus.EventBus;
import org.asf.centuria.modules.eventbus.EventListener;
import org.asf.centuria.modules.eventbus.EventObject;
import org.asf.centuria.modules.eventbus.IEventReceiver;
import org.asf.centuria.modules.eventbus.SupplierEventObject;
import org.asf.centuria.modules.eventbus.conditions.EventConditionConstructor;
import org.asf.centuria.modules.eventbus.conditions.RepeatableTarget;
import org.asf.centuria.modules.eventbus.conditions.interfaces.IEventConditionConstructor;
import org.asf.centuria.modules.eventbus.conditions.interfaces.IGenericEventCondition;
import org.asf.centuria.modules.eventbus.impl.asm.BinaryClassLoader;
import org.asf.centuria.modules.eventbus.impl.asm.IEventDispatcher;
import org.asf.centuria.modules.eventbus.impl.asm.IStaticEventDispatcher;
import org.asf.centuria.modules.eventbus.impl.asm.IStaticSupplierEventDispatcher;
import org.asf.centuria.modules.eventbus.impl.asm.ISupplierEventDispatcher;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public class EventBusImpl extends EventBus {

	private EventBus parent;
	private Logger eventLog = LogManager.getLogger("EVENTBUS");

	private ArrayList<String> loadedEvents = new ArrayList<String>();
	private HashMap<String, ArrayList<Consumer<?>>> listeners = new HashMap<String, ArrayList<Consumer<?>>>();
	private ArrayList<String> loadedStaticEventListeners = new ArrayList<String>();
	private ArrayList<IEventReceiver> boundReceivers = new ArrayList<IEventReceiver>();

	private static BinaryClassLoader binLoader;
	private static HashMap<String, IStaticEventDispatcher> staticDispatchers = new HashMap<String, IStaticEventDispatcher>();
	private static HashMap<String, IEventDispatcher> objDispatchers = new HashMap<String, IEventDispatcher>();
	private static HashMap<String, IStaticSupplierEventDispatcher> staticSupDispatchers = new HashMap<String, IStaticSupplierEventDispatcher>();
	private static HashMap<String, ISupplierEventDispatcher> objSupDispatchers = new HashMap<String, ISupplierEventDispatcher>();

	private static HashMap<String, IEventConditionConstructor> conditionCtors = new HashMap<String, IEventConditionConstructor>();

	private class EventCondData {
		public IEventConditionConstructor ctor;
		public Annotation anno;
	}

	public static void initClassLoader(ClassLoader parent) {
		if (binLoader != null)
			throw new IllegalStateException();
		binLoader = new BinaryClassLoader(parent);
	}

	@Override
	@SuppressWarnings("unchecked")
	public void addAllEventsFromReceiver(IEventReceiver receiver) {
		// Check
		synchronized (boundReceivers) {
			if (boundReceivers.contains(receiver))
				return;
			boundReceivers.add(receiver);
		}

		// Log subscription
		eventLog.info("Registering all events in " + receiver.getClass().getTypeName() + "...");

		// Loop through the class and register events
		try {
			// Preload
			binLoader.loadClass(receiver.getClass().getTypeName());
		} catch (ClassNotFoundException e) {
		}
		for (Method meth : receiver.getClass().getMethods()) {
			if (meth.isAnnotationPresent(EventListener.class) && Modifier.isPublic(meth.getModifiers())
					&& !Modifier.isAbstract(meth.getModifiers())) {
				// Find the event object
				if (meth.getParameterCount() == 1 && EventObject.class.isAssignableFrom(meth.getParameterTypes()[0])) {
					// Find event path
					Class<?> eventType = meth.getParameterTypes()[0];
					if (EventObject.class.isAssignableFrom(eventType)) {
						// Check if static
						if (Modifier.isStatic(meth.getModifiers())) {
							String stPth = receiver.getClass().getTypeName() + "_" + meth.getName();
							synchronized (loadedStaticEventListeners) {
								// Check if the static event listener is already loaded, if it is, skip this
								// event listener to prevent duplicate registration of static event listeners
								if (!loadedStaticEventListeners.contains(stPth))
									loadedStaticEventListeners.add(stPth);
								else
									continue;
							}
						}

						try {
							// Preload
							binLoader.loadClass(eventType.getTypeName());
						} catch (ClassNotFoundException e) {
						}

						// Make accessible
						meth.setAccessible(true);

						// Find all conditions
						ArrayList<EventCondData> conditionCtors = new ArrayList<EventCondData>();
						findConditions(meth, conditionCtors);

						// Add listener
						String path = eventType.getTypeName();
						boolean toLoad = false;
						synchronized (loadedEvents) {
							if (!loadedEvents.contains(path)) {
								loadedEvents.add(path);
								toLoad = true;
							}
						}
						if (toLoad) {
							// Load event
							EventObject ev = null;
							try {
								ev = (EventObject) eventType.getConstructor().newInstance();
							} catch (Exception e) {
							}
							if (ev != null)
								ev.onRegister(this);
						}
						synchronized (listeners) {
							ArrayList<Consumer<?>> events = new ArrayList<Consumer<?>>();
							if (listeners.containsKey(path))
								events = new ArrayList<Consumer<?>>(listeners.get(path));
							EventContainerListener l = new EventContainerListener();
							l.owner = receiver;

							// Check if supplier
							if (!SupplierEventObject.class.isAssignableFrom(eventType)) {
								// Get dispatcher
								if (!Modifier.isStatic(meth.getModifiers())) {
									// Regular
									IEventDispatcher disp = getDispatcher(receiver.getClass(), meth, eventType);
									l.delegate = t -> {
										// Go through conditions
										for (EventCondData cond : conditionCtors) {
											IGenericEventCondition condition = cond.ctor.construct(receiver, meth,
													(EventObject) t, cond.anno, EventBusImpl.this);
											if (!condition.match(receiver, meth, (EventObject) t))
												return;
										}

										// Dispatch
										disp.dispatch(receiver, (EventObject) t);
									};
								} else {
									// Static
									IStaticEventDispatcher disp = getStaticDispatcher(receiver.getClass(), meth,
											eventType);
									l.delegate = t -> {
										// Go through conditions
										for (EventCondData cond : conditionCtors) {
											IGenericEventCondition condition = cond.ctor.construct(null, meth,
													(EventObject) t, cond.anno, EventBusImpl.this);
											if (condition.supportsStatic()
													&& !condition.match(null, meth, (EventObject) t))
												return;
										}

										// Dispatch
										disp.dispatch((EventObject) t);
									};
								}
							} else {
								// Get dispatcher
								if (!Modifier.isStatic(meth.getModifiers())) {
									// Regular
									ISupplierEventDispatcher disp = getSupplierDispatcher(receiver.getClass(), meth,
											eventType);
									l.delegate = t -> {
										// Go through conditions
										for (EventCondData cond : conditionCtors) {
											IGenericEventCondition condition = cond.ctor.construct(receiver, meth,
													(EventObject) t, cond.anno, EventBusImpl.this);
											if (!condition.match(receiver, meth, (EventObject) t))
												return;
										}

										// Dispatch
										@SuppressWarnings("rawtypes")
										SupplierEventObject e = (SupplierEventObject<?>) t;
										Object ret = disp.dispatch(receiver, e);
										if (ret != null) {
											e.setResult(ret);
										}
									};
								} else {
									// Static
									IStaticSupplierEventDispatcher disp = getStaticSupplierDispatcher(
											receiver.getClass(), meth, eventType);
									l.delegate = t -> {
										// Go through conditions
										for (EventCondData cond : conditionCtors) {
											IGenericEventCondition condition = cond.ctor.construct(null, meth,
													(EventObject) t, cond.anno, EventBusImpl.this);
											if (condition.supportsStatic()
													&& !condition.match(null, meth, (EventObject) t))
												return;
										}

										// Dispatch
										@SuppressWarnings("rawtypes")
										SupplierEventObject e = (SupplierEventObject<?>) t;
										Object ret = disp.dispatch(e);
										if (ret != null) {
											e.setResult(ret);
										}
									};
								}
							}
							eventLog.debug("Attaching event handler " + receiver.getClass().getTypeName() + ":"
									+ meth.getName() + " to event " + eventType.getTypeName());
							events.add(l);
							listeners.put(path, events);
						}
					}
				}
			}
		}
	}

	private void findConditions(Method meth, ArrayList<EventCondData> conditionCtors) {
		for (Annotation anno : meth.getAnnotations()) {
			findConditions(meth, anno, conditionCtors);
		}
		findConditions(meth.getDeclaringClass(), conditionCtors);
	}

	private void findConditions(Class<?> target, ArrayList<EventCondData> conditionCtors) {
		for (Annotation anno : target.getAnnotations()) {
			findConditions(target, anno, conditionCtors);
		}
		if (target.getTypeName().equals(Object.class.getTypeName()))
			return;
		findConditions(target.getSuperclass(), conditionCtors);
	}

	private void findConditions(AnnotatedElement parent, Annotation anno, ArrayList<EventCondData> conditionCtors) {
		if (anno.annotationType().isAnnotationPresent(RepeatableTarget.class)) {
			for (Annotation annot : parent
					.getAnnotationsByType(anno.annotationType().getAnnotation(RepeatableTarget.class).value())) {
				findConditions(parent, annot, conditionCtors);
			}
		}
		if (anno.annotationType().isAnnotationPresent(EventConditionConstructor.class)) {
			// Get constructor info
			EventConditionConstructor constrInfo = anno.annotationType().getAnnotation(EventConditionConstructor.class);
			Class<? extends IEventConditionConstructor> ctorType = constrInfo.value();
			synchronized (EventBusImpl.conditionCtors) {
				if (EventBusImpl.conditionCtors.containsKey(ctorType.getTypeName())) {
					EventCondData d = new EventCondData();
					d.ctor = EventBusImpl.conditionCtors.get(ctorType.getTypeName());
					d.anno = anno;
					conditionCtors.add(d);
					return;
				}

				// Constructor
				Constructor<? extends IEventConditionConstructor> ctor;
				try {
					ctor = ctorType.getConstructor();
				} catch (NoSuchMethodException | SecurityException e) {
					throw new RuntimeException("Failed to create instance of condition constructor type "
							+ ctorType.getTypeName() + ", no suitable constructor found.", e);
				}

				// Create instance
				try {
					IEventConditionConstructor inst = ctor.newInstance();
					EventBusImpl.conditionCtors.put(ctorType.getTypeName(), inst);
					EventCondData d = new EventCondData();
					d.ctor = inst;
					d.anno = anno;
					conditionCtors.add(d);
				} catch (InstantiationException | IllegalAccessException | IllegalArgumentException
						| InvocationTargetException e) {
					throw new RuntimeException(
							"Failed to create instance of condition constructor type " + ctorType.getTypeName(), e);
				}
			}
		}
	}

	@Override
	public void removeAllEventsFromReceiver(IEventReceiver receiver) {
		// Check
		synchronized (boundReceivers) {
			if (!boundReceivers.contains(receiver))
				return;
			boundReceivers.remove(receiver);
		}

		// Log subscription
		eventLog.info("De-registering all events in " + receiver.getClass().getTypeName() + "...");

		// Loop through the class and de-register events
		for (Method meth : receiver.getClass().getMethods()) {
			if (meth.isAnnotationPresent(EventListener.class) && Modifier.isPublic(meth.getModifiers())
					&& !Modifier.isAbstract(meth.getModifiers())) {
				// Find the event object
				if (meth.getParameterCount() == 1 && EventObject.class.isAssignableFrom(meth.getParameterTypes()[0])) {
					// Find event path
					Class<?> eventType = meth.getParameterTypes()[0];
					if (EventObject.class.isAssignableFrom(eventType)) {
						// Check if static
						if (Modifier.isStatic(meth.getModifiers())) {
							String stPth = receiver.getClass().getTypeName() + "_" + meth.getName();
							synchronized (loadedStaticEventListeners) {
								// Check if the static event listener is known
								if (loadedStaticEventListeners.contains(stPth)) {
									// It is, check if there are any other listeners of the same type
									boolean found = false;
									synchronized (boundReceivers) {
										for (IEventReceiver rec : boundReceivers) {
											if (meth.getDeclaringClass().isAssignableFrom(rec.getClass())) {
												found = true;
												break;
											}
										}
									}
									if (found) {
										// Still present
										// Skip de-registering
										continue;
									}

									// De-register
									loadedStaticEventListeners.remove(stPth);
								}
							}
						}

						// Find listeners
						meth.setAccessible(true);
						String path = eventType.getTypeName();
						if (listeners.containsKey(path)) {
							synchronized (listeners) {
								ArrayList<Consumer<?>> events = new ArrayList<Consumer<?>>();
								if (listeners.containsKey(path))
									events = new ArrayList<Consumer<?>>(listeners.get(path));

								// Remove
								Consumer<?>[] evs = events.toArray(t -> new Consumer<?>[t]);
								for (Consumer<?> ev : evs) {
									if (ev instanceof EventContainerListener) {
										EventContainerListener l = (EventContainerListener) ev;
										if (l.owner == receiver) {
											eventLog.debug("Detaching event handler "
													+ receiver.getClass().getTypeName() + ":" + meth.getName()
													+ " from event " + eventType.getTypeName());
											events.remove(l);
										}
									}
								}

								listeners.put(path, events);
							}
						}
					}

				}
			}
		}
	}

	@Override
	public <T extends EventObject> void addEventHandler(Class<T> eventClass, Consumer<T> eventHandler) {
		// Load event
		String path = eventClass.getTypeName();
		boolean toLoad = false;
		synchronized (loadedEvents) {
			if (!loadedEvents.contains(path)) {
				loadedEvents.add(path);
				toLoad = true;
			}
		}
		if (toLoad) {
			// Load event
			EventObject ev = null;
			try {
				ev = (EventObject) eventClass.getConstructor().newInstance();
			} catch (Exception e) {
			}
			if (ev != null)
				ev.onRegister(this);
		}

		// Register
		synchronized (listeners) {
			ArrayList<Consumer<?>> events = new ArrayList<Consumer<?>>();
			if (listeners.containsKey(path))
				events = new ArrayList<Consumer<?>>(listeners.get(path));

			events.add(eventHandler);
			eventLog.debug("Attaching event handler " + eventHandler + " to event " + eventClass.getTypeName());

			listeners.put(path, events);
		}
	}

	@Override
	public <T extends EventObject> void removeEventHandler(Class<T> eventClass, Consumer<T> eventHandler) {
		// Add listener
		String path = eventClass.getTypeName();
		if (!listeners.containsKey(path))
			return;
		synchronized (listeners) {
			ArrayList<Consumer<?>> events = new ArrayList<Consumer<?>>();
			if (listeners.containsKey(path))
				events = new ArrayList<Consumer<?>>(listeners.get(path));

			events.remove(eventHandler);
			eventLog.debug("Detaching event handler " + eventHandler + " from event " + eventClass.getTypeName());

			listeners.put(path, events);
		}
	}

	@Override
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void dispatchEvent(EventObject event) {
		if (parent != null)
			parent.dispatchEvent(event);
		if (listeners.containsKey(event.getClass().getTypeName())) {
			// Dispatch event
			ArrayList<Consumer<?>> events = this.listeners.get(event.getClass().getTypeName());
			for (Consumer ev : events) {
				ev.accept(event);
			}
		}
	}

	@Override
	public EventBus createBus() {
		EventBusImpl ev = new EventBusImpl();
		ev.parent = this;
		return ev;
	}

	private IStaticEventDispatcher getStaticDispatcher(Class<?> type, Method method, Class<?> eventObject) {
		String eventPth = type.getTypeName().replace(".", "/") + "$" + eventObject.getTypeName().replace(".", "/") + "$"
				+ method.getName().replace(".", "/");
		synchronized (staticDispatchers) {
			if (staticDispatchers.containsKey(eventPth))
				return staticDispatchers.get(eventPth);

			// Generate bytecode
			ClassNode syn = new ClassNode(Opcodes.ASM9);
			syn.superName = "java/lang/Object";
			syn.version = Opcodes.V1_6;
			syn.name = eventPth + "$Synthetic_" + System.currentTimeMillis();
			syn.nestHostClass = type.getTypeName().replace(".", "/");
			syn.interfaces.add("org/asf/centuria/modules/eventbus/impl/asm/IStaticEventDispatcher");
			syn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC;

			// Generate constructor
			MethodNode init = new MethodNode();
			init.access = Opcodes.ACC_PUBLIC;
			init.name = "<init>";
			init.desc = "()V";
			init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			init.instructions
					.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
			init.instructions.add(new InsnNode(Opcodes.RETURN));
			syn.methods.add(init);

			// Generate dispatcher
			MethodNode setup = new MethodNode();
			setup.name = "dispatch";
			setup.desc = "(Lorg/asf/centuria/modules/eventbus/EventObject;)V";
			setup.access = Opcodes.ACC_PUBLIC;

			setup.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
			setup.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, eventObject.getTypeName().replace(".", "/")));
			setup.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, type.getTypeName().replace(".", "/"),
					method.getName(),
					"(" + getDescriptors(eventObject) + ")" + getDescriptor(method.getReturnType().getTypeName())));
			if (!method.getReturnType().getTypeName().equals("void"))
				setup.instructions.add(new InsnNode(Opcodes.POP));
			setup.instructions.add(new InsnNode(Opcodes.RETURN));
			syn.methods.add(setup);

			// Create instance
			Class<IStaticEventDispatcher> dp = binLoader.loadClassBinary(syn, IStaticEventDispatcher.class);
			try {
				IStaticEventDispatcher inst = dp.getConstructor().newInstance();
				staticDispatchers.put(eventPth, inst);
				return inst;
			} catch (InstantiationException | IllegalAccessException | IllegalArgumentException
					| InvocationTargetException | NoSuchMethodException | SecurityException e) {
				throw new RuntimeException(e);
			}
		}
	}

	private IEventDispatcher getDispatcher(Class<?> type, Method method, Class<?> eventType) {
		String eventPth = type.getTypeName().replace(".", "/") + "$" + eventType.getTypeName().replace(".", "/") + "$"
				+ method.getName().replace(".", "/");
		synchronized (objDispatchers) {
			if (objDispatchers.containsKey(eventPth))
				return objDispatchers.get(eventPth);

			// Generate bytecode
			ClassNode syn = new ClassNode(Opcodes.ASM9);
			syn.superName = "java/lang/Object";
			syn.nestHostClass = type.getTypeName().replace(".", "/");
			syn.version = Opcodes.V1_6;
			syn.name = eventPth + "$Synthetic_" + System.currentTimeMillis();
			syn.nestHostClass = type.getTypeName().replace(".", "/");
			syn.interfaces.add("org/asf/centuria/modules/eventbus/impl/asm/IEventDispatcher");
			syn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC;

			// Generate constructor
			MethodNode init = new MethodNode();
			init.access = Opcodes.ACC_PUBLIC;
			init.name = "<init>";
			init.desc = "()V";
			init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			init.instructions
					.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
			init.instructions.add(new InsnNode(Opcodes.RETURN));
			syn.methods.add(init);

			// Generate dispatcher
			MethodNode setup = new MethodNode();
			setup.name = "dispatch";
			setup.desc = "(Lorg/asf/centuria/modules/eventbus/IEventReceiver;Lorg/asf/centuria/modules/eventbus/EventObject;)V";
			setup.access = Opcodes.ACC_PUBLIC;

			setup.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
			setup.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getTypeName().replace(".", "/")));
			setup.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
			setup.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, eventType.getTypeName().replace(".", "/")));
			setup.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, type.getTypeName().replace(".", "/"),
					method.getName(),
					"(" + getDescriptors(eventType) + ")" + getDescriptor(method.getReturnType().getTypeName())));
			if (!method.getReturnType().getTypeName().equals("void"))
				setup.instructions.add(new InsnNode(Opcodes.POP));
			setup.instructions.add(new InsnNode(Opcodes.RETURN));
			syn.methods.add(setup);

			// Create instance
			Class<IEventDispatcher> dp = binLoader.loadClassBinary(syn, IEventDispatcher.class);
			try {
				IEventDispatcher inst = dp.getConstructor().newInstance();
				objDispatchers.put(eventPth, inst);
				return inst;
			} catch (InstantiationException | IllegalAccessException | IllegalArgumentException
					| InvocationTargetException | NoSuchMethodException | SecurityException e) {
				throw new RuntimeException(e);
			}
		}
	}

	private IStaticSupplierEventDispatcher getStaticSupplierDispatcher(Class<?> type, Method method,
			Class<?> eventObject) {
		String eventPth = type.getTypeName().replace(".", "/") + "/" + eventObject.getTypeName().replace(".", "/") + "_"
				+ method.getName().replace(".", "/");
		synchronized (staticSupDispatchers) {
			if (staticSupDispatchers.containsKey(eventPth))
				return staticSupDispatchers.get(eventPth);

			// Generate bytecode
			ClassNode syn = new ClassNode(Opcodes.ASM9);
			syn.superName = "java/lang/Object";
			syn.version = Opcodes.V1_6;
			syn.name = eventPth + "$Synthetic_" + System.currentTimeMillis();
			syn.interfaces.add("org/asf/centuria/modules/eventbus/impl/asm/IStaticSupplierEventDispatcher");
			syn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC;

			// Generate constructor
			MethodNode init = new MethodNode();
			init.access = Opcodes.ACC_PUBLIC;
			init.name = "<init>";
			init.desc = "()V";
			init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			init.instructions
					.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
			init.instructions.add(new InsnNode(Opcodes.RETURN));
			syn.methods.add(init);

			// Generate dispatcher
			MethodNode setup = new MethodNode();
			setup.name = "dispatch";
			setup.desc = "(Lorg/asf/centuria/modules/eventbus/SupplierEventObject;)Ljava/lang/Object;";
			setup.access = Opcodes.ACC_PUBLIC;

			setup.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
			setup.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, eventObject.getTypeName().replace(".", "/")));
			setup.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, type.getTypeName().replace(".", "/"),
					method.getName(),
					"(" + getDescriptors(eventObject) + ")" + getDescriptor(method.getReturnType().getTypeName())));
			setup.instructions.add(new InsnNode(Opcodes.ARETURN));
			syn.methods.add(setup);

			// Create instance
			Class<IStaticSupplierEventDispatcher> dp = binLoader.loadClassBinary(syn,
					IStaticSupplierEventDispatcher.class);
			try {
				IStaticSupplierEventDispatcher inst = dp.getConstructor().newInstance();
				staticSupDispatchers.put(eventPth, inst);
				return inst;
			} catch (InstantiationException | IllegalAccessException | IllegalArgumentException
					| InvocationTargetException | NoSuchMethodException | SecurityException e) {
				throw new RuntimeException(e);
			}
		}
	}

	private ISupplierEventDispatcher getSupplierDispatcher(Class<?> type, Method method, Class<?> eventType) {
		String eventPth = type.getTypeName().replace(".", "/") + "/" + eventType.getTypeName().replace(".", "/") + "_"
				+ method.getName().replace(".", "/");
		synchronized (objSupDispatchers) {
			if (objSupDispatchers.containsKey(eventPth))
				return objSupDispatchers.get(eventPth);

			// Generate bytecode
			ClassNode syn = new ClassNode(Opcodes.ASM9);
			syn.superName = "java/lang/Object";
			syn.version = Opcodes.V1_6;
			syn.name = eventPth + "$Synthetic_" + System.currentTimeMillis();
			syn.interfaces.add("org/asf/centuria/modules/eventbus/impl/asm/ISupplierEventDispatcher");
			syn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC;

			// Generate constructor
			MethodNode init = new MethodNode();
			init.access = Opcodes.ACC_PUBLIC;
			init.name = "<init>";
			init.desc = "()V";
			init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			init.instructions
					.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
			init.instructions.add(new InsnNode(Opcodes.RETURN));
			syn.methods.add(init);

			// Generate dispatcher
			MethodNode setup = new MethodNode();
			setup.name = "dispatch";
			setup.desc = "(Lorg/asf/centuria/modules/eventbus/IEventReceiver;Lorg/asf/centuria/modules/eventbus/SupplierEventObject;)Ljava/lang/Object;";
			setup.access = Opcodes.ACC_PUBLIC;

			setup.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
			setup.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getTypeName().replace(".", "/")));
			setup.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
			setup.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, eventType.getTypeName().replace(".", "/")));
			setup.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, type.getTypeName().replace(".", "/"),
					method.getName(),
					"(" + getDescriptors(eventType) + ")" + getDescriptor(method.getReturnType().getTypeName())));
			setup.instructions.add(new InsnNode(Opcodes.ARETURN));
			syn.methods.add(setup);

			// Create instance
			Class<ISupplierEventDispatcher> dp = binLoader.loadClassBinary(syn, ISupplierEventDispatcher.class);
			try {
				ISupplierEventDispatcher inst = dp.getConstructor().newInstance();
				objSupDispatchers.put(eventPth, inst);
				return inst;
			} catch (InstantiationException | IllegalAccessException | IllegalArgumentException
					| InvocationTargetException | NoSuchMethodException | SecurityException e) {
				throw new RuntimeException(e);
			}
		}
	}

	static final Map<Class<?>, Class<?>> PRIMITIVES = Map.of(byte.class, Byte.class, short.class, Short.class,
			int.class, Integer.class, long.class, Long.class, float.class, Float.class, double.class, Double.class,
			boolean.class, Boolean.class, char.class, Character.class);
	private static Map<Character, String> descriptors = Map.of('V', "void", 'Z', "boolean", 'I', "int", 'J', "long",
			'D', "double", 'F', "float", 'S', "short", 'C', "char", 'B', "byte");

	private static String getDescriptors(Class<?>... types) {
		StringBuilder b = new StringBuilder();
		for (Class<?> type : types) {
			b.append(getDescriptor(type.getTypeName()));
		}
		return b.toString();
	}

	private static String getDescriptor(String type) {
		String prefix = "";
		while (type.contains("[]")) {
			prefix += "[";
			type = type.substring(0, type.lastIndexOf("["));
		}
		int i = 0;
		for (String desc : descriptors.values()) {
			if (desc.equals(type))
				return prefix + descriptors.keySet().toArray(t -> new Character[t])[i].toString();
			i++;
		}
		if (type == "")
			return "";
		return prefix + "L" + type.replaceAll("\\.", "/") + ";";
	}

	@SuppressWarnings("rawtypes")
	private static class EventContainerListener implements Consumer {

		public IEventReceiver owner;
		public Consumer delegate;

		@Override
		@SuppressWarnings("unchecked")
		public void accept(Object t) {
			delegate.accept(t);
		}

	}

}
