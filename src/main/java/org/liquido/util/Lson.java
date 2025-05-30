package org.liquido.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Liquido json builder "LSON". Yet another JSON builder with a very nice fluid API.
 */
public class Lson extends HashMap<String, Object> implements Map<String, Object> {

	public static ObjectMapper mapper = new ObjectMapper();

	/**
	 * Create a builder for JSON.
	 * Use its fluid syntax to add attributes
	 * <pre>String json = new Lson().put("some.attr", "value").toString()</pre>
	 */
	public Lson() { }

	/**
	 * Create a Lson from a java.util.Map
	 * @param map map from String -> Object
	 */
	public Lson(Map<? extends String, ?> map) {
		super(map);
	}

	/**
	 * Create an Lson from a json string
	 * @param jsonString must be a valid Json String that can be read by Jackson
	 * @throws JsonProcessingException when jsonString is invalid
	 */
	public Lson(String jsonString) throws JsonProcessingException {
		// is this a crude hack or a missing feature in Java? Java is not good in handling JSON (from a developer perspective) compared for example to JavaScript
		this(jsonString != null ? mapper.readValue(jsonString, Map.class) : new HashMap<>());
	}

	/**
	 * Create an Lson with one initial value at the given path
	 * Can be chained like this: new Lson("key1", "value").put("key2", "value2")
	 * @param path path to value. Can be just the key or a path like "one.two.three"
	 * @param value the value to put at the given path
	 */
	public Lson(String path, Object value) {
		this();
		this.put(path, value);
	}

	/** Factory method.  Lson.builder().put("name", someValue).... */
	public static Lson builder() {
		return new Lson();
	}

	/** Factory method - shortcut for very simple JSON:  Lson.builder("key", "value") */
	public static Lson builder(String key, Object value) {
		Lson lson = Lson.builder();
		return lson.put(key, value);
	}

	/**
	 * Creates an immutable copy of this Map.
	 * You actually don't need to call this.
	 * You can keep working with the (mutable) return value of any Lson method here.
	 */
	public Lson build() {
		return new Lson(Collections.unmodifiableMap(this));
	}

	/** Powerful fluid api to add key=value pairs
	 * <pre>myLson.put("name", someValue).put("key2", anotherValue).put("key3.neested.child.key", valueObj)...</pre>
	 *
	 * Tip: value can also be <pre>Collections.singletonMap("attribute", "single value in map")</pre>
	 *
	 * @param path json key or dot separated json path
	 * @param value any java object. Will be serialized with Jackson
	 */
	public Lson put(String path, Object value) {
		int idx = path.indexOf(".");
		if (idx > 0) {
			String key       = path.substring(0, idx);
			String childPath = path.substring(idx+1);
			Map child = (Map)super.get(key);
			if (child != null) {
				child.put(childPath, value);
			} else {
				super.put(key, Lson.builder(childPath, value));
			}
		} else {
			super.put(path, value);
		}
		return this;  // return self instance for chaining
	}

	/**
	 * Get the value under a given path.
	 * @param path path with dot notation, eg. "parent.child.attribute"
	 * @return the value at this path deep down from the hierarchy. Value may be null.
	 * @throws Exception when a path element does not exist.
	 */
	public Object get(String path) throws Exception {
		int idx = path.indexOf(".");
		if (idx > 0) {
			String key       = path.substring(0, idx);
			String childPath = path.substring(idx+1);
			//TODO: get from array
			Object child = this.get(key);
			if (child == null) throw new Exception("No element under path "+path);
			if (child instanceof Lson) {
				return ((Lson)child).get(childPath);
			} else {
				throw new Exception("Cannot get "+path +" from child. Need an Lson");
			}
		} else {
			return super.get(path);  // last element of path is just a key. We just simply return the value under that key from the Map
		}
	}

	/**
	 * Put value under that path, but only if value != null.
	 * This prevents creating attributes with empty values.
	 * @param path
	 * @param value
	 * @return
	 */
	public Lson putIfValueIsPresent(String path, Object value) {
		if (value == null) return this;
		return this.put(path, value);
	}

	/**
	 * Add a json attribute with an <b>array of strings</b> as value.
	 * Not any JSON builder I know of has this obvious helper !! :-)
	 * <pre>String validJson = Lson.builder().putArray("jsonArray", "arrayElemOne", "arrayElemTwo", "arrayElemThree").toString();</pre>
	 * @param key attribute name
	 * @param values a list of strings. Can have any length
	 * @return this for chaining of further fluid calls
	 */
	public Lson putArray(String key, String... values) {
		super.put(key, values);
		return this;
	}

	public  Lson putArray(String key, Iterable values) {
		super.put(key, values);
		return this;
	}

	public String toUrlEncodedParams() {
		StringBuffer buf = new StringBuffer();
		for(String key : this.keySet()) {
			buf.append(key);
			buf.append("=");
			try {
				buf.append(URLEncoder.encode((String)super.get(key), "UTF-8"));
			} catch (UnsupportedEncodingException e) {
				throw new RuntimeException("Cannot URL encode value of param '"+key+"'", e);
			}
			buf.append("&");
		}
		buf.deleteCharAt(buf.length()-1);
		return buf.toString();
	}

	public String toPrettyString() {
		try {
			mapper.enable(SerializationFeature.INDENT_OUTPUT);
			String indentedJsonStr = mapper.writeValueAsString(this);
			mapper.disable(SerializationFeature.INDENT_OUTPUT);
			return indentedJsonStr;
		} catch (JsonProcessingException e) {
			throw new RuntimeException("Cannot write out JSON: "+e, e);
		}
	}

	public String toString() {
		try {
			return mapper.writeValueAsString(this);
		} catch (JsonProcessingException e) {
			throw new RuntimeException("Cannot write out JSON: "+e, e);
		}
	}

}