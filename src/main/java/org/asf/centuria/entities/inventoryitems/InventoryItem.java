package org.asf.centuria.entities.inventoryitems;

import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.asf.centuria.entities.components.ComponentManager;
import org.asf.centuria.entities.components.InventoryItemComponent;
import org.asf.centuria.enums.inventory.InventoryType;

import com.google.gson.JsonObject;

/**
 * The base class for all inventory items. The idea behind this is you want to
 * code abstract methods to add the required components to the item. This class
 * takes care of converting back and forth from JsonObjects.
 * 
 * @author Owenvii
 */
public abstract class InventoryItem {
	// json constants
	public final static String DEF_ID_PROPERTY_NAME = "defId";
	public final static String UUID_PROPERTY_NAME = "id";
	public final static String INV_TYPE_PROPERTY_NAME = "type";
	public final static String COMPONENTS_PROPERTY_NAME = "components";

	// object variables
	public String defId;
	public String uuid;
	public InventoryType invType;
	private Map<String, InventoryItemComponent> components = new LinkedHashMap<String, InventoryItemComponent>();

	public InventoryItem() {
		this.defId = "-1";
		this.uuid = "";
		this.invType = null;
	}

	/**
	 * Base constructor for all inventory items.
	 * 
	 * @param defId   The defId of the inventory item.
	 * @param uuid    The unique identifier of the item.
	 * @param invType The inventory number/type this item belongs in.
	 */
	public InventoryItem(String defId, String uuid, InventoryType invType) {
		this.defId = defId;
		this.uuid = uuid;
		this.invType = invType;
	}

	/**
	 * Converts a jsonObject to an inventory item. This method is meant to be used
	 * by a extending class. Call the super method to populate default properties.
	 * 
	 * @param object The jsonObject to retrieve properties from.
	 * @throws SecurityException
	 * @throws NoSuchMethodException
	 * @throws InvocationTargetException
	 * @throws IllegalArgumentException
	 * @throws IllegalAccessException
	 * @throws InstantiationException
	 */
	public void fromJsonObject(JsonObject object) throws InstantiationException, IllegalAccessException,
			IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException {
		// this continues adding the fields from the object.

		defId = object.get(DEF_ID_PROPERTY_NAME).getAsString();
		uuid = object.get(UUID_PROPERTY_NAME).getAsString();
		invType = InventoryType.get(object.get(INV_TYPE_PROPERTY_NAME).getAsInt());

		// get components

		JsonObject componentLevel = object.get("components").getAsJsonObject();

		for (var componentJson : componentLevel.entrySet()) {
			var type = ComponentManager.getComponentTypeFromComponentName(componentJson.getKey());
			var item = InventoryItemComponent.fromJson(type, componentJson.getValue().getAsJsonObject());
			components.put(item.getComponentName(), item);
		}
	}

	/**
	 * Converts this instance of the inventory object to a json object. This method
	 * is meant to be used by a extending class. Call the super method to populate
	 * default properties.
	 * 
	 * @return The jsonObject after populating the default values.
	 */
	public JsonObject toJsonObject() {
		// this continues adding the fields to the object.
		var newObject = new JsonObject();
		newObject.addProperty(DEF_ID_PROPERTY_NAME, defId);
		newObject.addProperty(UUID_PROPERTY_NAME, uuid);
		newObject.addProperty(INV_TYPE_PROPERTY_NAME, invType.invTypeId);

		var componentObject = new JsonObject();

		for (var set : components.entrySet()) {
			var componentChildObject = set.getValue().toJson();
			componentObject.add(set.getKey(), componentChildObject);
		}

		// add component object
		newObject.add(COMPONENTS_PROPERTY_NAME, componentObject);

		return newObject;
	}

	/**
	 * This method is used to add components to the item.
	 * 
	 * @param component The component to add.
	 */
	public void addComponent(InventoryItemComponent component) {
		// adds the component..
		components.put(component.getComponentName(), component);
	}

	/**
	 * Checks if the components have any components with its name.
	 * 
	 * @param component The component to look for.
	 * @return Whether the component was found or not.
	 */
	public boolean hasComponent(InventoryItemComponent component) {
		boolean result = false;

		for (var set : components.entrySet()) {
			if (set.getKey() == component.getComponentName()) {
				result = true;
				break;
			}
		}

		return result;
	}

	/**
	 * Gets a component given its component name.
	 * 
	 * @param componentName The name of the component.
	 * @return The inventory item component.
	 */
	public InventoryItemComponent getComponent(String componentName) {
		InventoryItemComponent result = null;

		for (var set : components.entrySet()) {
			if (set.getKey() == componentName) {
				result = set.getValue();
				break;
			}
		}

		return result;
	}

	/**
	 * Sets a component. That means it will replace a component if it detects it as
	 * a duplicate.
	 * 
	 * @param component
	 */
	public void SetComponent(InventoryItemComponent component) {

		// TODO: Is the behaviour of replace acceptable for me not to need this check?
		if (components.containsKey(component.getComponentName())) {
			components.replace(component.getComponentName(), component);
		} else {
			components.put(component.getComponentName(), component);
		}
	}

	/**
	 * Gets the inv type for the item.
	 */
	public abstract InventoryType getInventoryType();

}
