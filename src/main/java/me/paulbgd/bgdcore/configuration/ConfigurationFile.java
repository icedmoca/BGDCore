package me.paulbgd.bgdcore.configuration;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import me.paulbgd.bgdcore.BGDCore;
import me.paulbgd.bgdcore.json.JSONLocation;
import me.paulbgd.bgdcore.json.JSONTidier;
import me.paulbgd.bgdcore.reflection.ReflectionClass;
import me.paulbgd.bgdcore.reflection.ReflectionField;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import net.minidev.json.JSONStyle;
import net.minidev.json.JSONValue;
import org.apache.commons.io.FileUtils;
import org.bukkit.Location;
import org.bukkit.util.Vector;

public class ConfigurationFile {

    @Getter
    private final File file;
    private final HashMap<ReflectionField, Object> previous = new HashMap<>();
    private final ConfigurationType configurationType;

    public ConfigurationFile() {
        this(null);
    }

    public ConfigurationFile(File file) {
        this(file, ConfigurationType.STATIC);
    }

    public ConfigurationFile(File file, ConfigurationType configurationType) {
        this.file = file;
        this.configurationType = configurationType;
        try {
            // we need to call the following in the reverse order to fill our hashmap
            updateDefaults();
            updateJSON();
        } catch (Exception e) {
            BGDCore.debug("Failed to update configuration to file \"" + (file != null ? file.getAbsolutePath() : "") + "\"!");
            e.printStackTrace();
        }
    }

    public void update() throws Exception {
        updateJSON();
        updateDefaults();
    }

    protected JSONObject updateJSON() throws Exception {
        JSONObject jsonObject = getCurrent();
        if (jsonObject == null) {
            jsonObject = new JSONObject();
        }
        for (Map.Entry<ReflectionField, Object> entry : previous.entrySet()) {
            ReflectionField field = entry.getKey();
            Object value = entry.getValue();
            Object currentValue = checkFieldValue(field.getValue().getObject());
            if (!jsonObject.containsKey(field.getName())) {
                jsonObject.put(field.getName(), currentValue);
            } else {
                Object configValue = jsonObject.get(field.getName());
                // to allow us to use .equals on null, we'll use a little hack
                if (currentValue == null) {
                    currentValue = "null";
                }
                if (value == null) {
                    value = "null";
                }
                if (configValue == null) {
                    configValue = "null";
                }
                if (!currentValue.equals(entry.getValue()) && configValue.equals(value)) {
                    // the config contains the old value and we have a new one to set
                    jsonObject.put(field.getName(), currentValue.equals("null") ? null : currentValue);
                } else if (!configValue.equals(value)) {
                    field.setValue(checkValue(configValue.equals("null") ? null : configValue));
                }
            }
        }
        if (file != null) {
            try {
                FileUtils.write(file, JSONTidier.tidyJSON(jsonObject.toJSONString(JSONStyle.NO_COMPRESS)));
            } catch (Exception e) {
                BGDCore.getLogging().warning("Failed to save \"" + file.getAbsolutePath() + "\" to file!");
                e.printStackTrace();
            }
        }
        return jsonObject;
    }

    protected JSONObject getCurrent() throws IOException {
        if (file == null) {
            return null;
        }
        if (!file.exists() && !file.createNewFile()) {
            throw new FileNotFoundException("Failed to create file \"" + file.getAbsolutePath() + "\"!");
        } else {
            String json = FileUtils.readFileToString(file);
            if (json != null && !json.equals("") && !json.equals(" ")) {
                return (JSONObject) JSONValue.parse(json); // reload from file
            }
        }
        return null;
    }

    protected void updateDefaults() {
        // load to be previous fields
        for (Field field : getClass().getDeclaredFields()) {
            if (isValidField(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                ReflectionField reflectionField = new ReflectionField(configurationType == ConfigurationType.STATIC ? null : this, field);
                previous.put(reflectionField, reflectionField.getValue().getObject());
            }
        }
    }

    private static Object checkFieldValue(Object object) {
        if (object == null) {
            return object;
        }
        if (object instanceof List) {
            List<?> list = (List<?>) object;
            object = new JSONArray();
            for (Object o : list) {
                ((JSONArray) object).add(checkFieldValue(o));
            }
        } else if (object instanceof Location) {
            object = new JSONLocation((Location) object);
        } else if (object instanceof Vector) {
            Vector vector = (Vector) object;
            JSONObject newVector = new JSONObject();
            newVector.put("type", "vector");
            newVector.put("x", vector.getX());
            newVector.put("y", vector.getY());
            newVector.put("z", vector.getZ());
            object = newVector;
        } else if (object instanceof Enum) {
            Enum anEnum = (Enum) object;
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("type", "enum");
            jsonObject.put("enum", anEnum.getClass().getName());
            jsonObject.put("value", anEnum.name());
            object = jsonObject;
        } else if (object instanceof UUID) {
            JSONObject uuid = new JSONObject();
            uuid.put("type", "uuid");
            uuid.put("uuid", object.toString());
            return uuid;
        }
        return object;
    }

    private static Object checkValue(Object object) {
        if (object == null) {
            return object;
        }
        switch (object.getClass().getSimpleName()) {
            case "ArrayList":
            case "LinkedList":
                List list = (List) object;
                for (int i = 0, listSize = list.size(); i < listSize; i++) {
                    list.set(i, checkValue(list.get(i)));
                }
                return list;
            case "JSONArray":
                JSONArray jsonArray = (JSONArray) object;
                List<Object> list2 = new ArrayList<>();
                for (Object o : jsonArray) {
                    list2.add(checkValue(o));
                }
                return list2;
            case "JSONObject":
                JSONObject json = (JSONObject) object;
                if (json.containsKey("type")) {
                    switch ((String) json.get("type")) {
                        case "location":
                            object = new JSONLocation(json).getLocation();
                            break;
                        case "vector":
                            double x = Double.parseDouble(json.get("x").toString());
                            double y = Double.parseDouble(json.get("y").toString());
                            double z = Double.parseDouble(json.get("z").toString());
                            object = new Vector(x, y, z);
                            break;
                        case "enum":
                            try {
                                ReflectionClass enumClass = new ReflectionClass(Class.forName((String) json.get("enum")));
                                object = enumClass.getStaticMethod("valueOf", "string").invoke(json.get("value")).getObject();
                            } catch (ClassNotFoundException e) {
                                e.printStackTrace();
                            }
                            break;
                        case "uuid":
                            object = UUID.fromString((String) json.get("uuid"));
                            break;
                    }
                }
            default:
                return object;
        }
    }

    private boolean isValidField(int modifiers) {
        if (configurationType == ConfigurationType.STATIC) {
            return Modifier.isStatic(modifiers);
        } else {
            return !Modifier.isStatic(modifiers);
        }
    }

    public static enum ConfigurationType {
        OBJECT, STATIC;
    }

}
