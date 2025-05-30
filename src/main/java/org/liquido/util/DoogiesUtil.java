package org.liquido.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handy little utility functions that you <b>always</b> need.
 * See also
 *  - spring.core.util.*
 *  - org.springframework.util.Assert   CollectionUtils
 *  - or Google Guava
 */
public class DoogiesUtil {

	/**
	 * Check if s is null or empty or only contains spaces
	 * @return true when s is null, empty or contains only spaces
	 */
  public static boolean isEmpty(String s) {
    return s == null || s.trim().length() == 0;
  }


	/**
	 * null safe equals that also compares array elements.
	 * @param o1 object may be null
	 * @param o2 object may be null
	 * @return true if o1 equals o2 (also true, when o1 == o2 == null)
	 */
  public static boolean isEqual(Object o1, Object o2) {
		//copy of org.springframework.util.ObjectUtils.nullSafeEquals(o1, o2)
		if (o1 == o2) {
			return true;
		} else if (o1 != null && o2 != null) {
			if (o1.equals(o2)) {
				return true;
			} else {
				return o1.getClass().isArray() && o2.getClass().isArray() && arrayEquals(o1, o2);
			}
		} else {
			return false;
		}
	}

	public static boolean notEqual(Object o1, Object o2) {
  	return !isEqual(o1, o2);
	}

	public static boolean arrayEquals(Object o1, Object o2) {
		if (o1 instanceof Object[] && o2 instanceof Object[]) {
			return Arrays.equals((Object[])((Object[])o1), (Object[])((Object[])o2));
		} else if (o1 instanceof boolean[] && o2 instanceof boolean[]) {
			return Arrays.equals((boolean[])((boolean[])o1), (boolean[])((boolean[])o2));
		} else if (o1 instanceof byte[] && o2 instanceof byte[]) {
			return Arrays.equals((byte[])((byte[])o1), (byte[])((byte[])o2));
		} else if (o1 instanceof char[] && o2 instanceof char[]) {
			return Arrays.equals((char[])((char[])o1), (char[])((char[])o2));
		} else if (o1 instanceof double[] && o2 instanceof double[]) {
			return Arrays.equals((double[])((double[])o1), (double[])((double[])o2));
		} else if (o1 instanceof float[] && o2 instanceof float[]) {
			return Arrays.equals((float[])((float[])o1), (float[])((float[])o2));
		} else if (o1 instanceof int[] && o2 instanceof int[]) {
			return Arrays.equals((int[])((int[])o1), (int[])((int[])o2));
		} else if (o1 instanceof long[] && o2 instanceof long[]) {
			return Arrays.equals((long[])((long[])o1), (long[])((long[])o2));
		} else {
			return o1 instanceof short[] && o2 instanceof short[] ? Arrays.equals((short[])((short[])o1), (short[])((short[])o2)) : false;
		}
	}

	public static final String eMailRegEx = "\\b[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,64}\\b";
  public static final Pattern p = Pattern.compile(eMailRegEx);



	/**
	 * Check if s looks like an email adress.
	 * The official specification for the format of an email adress
	 * is alreay quite complex: https://tools.ietf.org/html/rfc2822#page-16
	 * This method uses a simple regular expression to validate email adresses.
	 * This method of course does not check if that email can actually receive mails.
	 *
	 * @param s an email adress to check for correct format.
	 * @return true if s looks like a valid email.
	 */
	public static boolean isEmail(String s) {
		if (s == null) return false;
		Matcher m = p.matcher(s);
		return m.matches();
	}

	/**
	 * Check if an Iterable (e.g. a Collection, List or Set) contains an element that
	 * fulfills a given Predicate. If so, then the <b>first</b> matching element is returned.
	 * What this method returns if predicate is null or iterable contains null element(s)
	 * solely depends on predicate.
	 *
	 * <h4>Example:</h4>
	 * <pre>Optional&lt;UserModel&gt; firstMatchingUser = find(userList, u -> u.name = "Hans")</pre>
	 *
	 * It's unbelievable to me that {@link Collections} still does not contain this.
	 *
	 * @param it an Iterable, e.g. a Collection, List or Set
	 * @param predicate A function that takes an element as input and returns true
	 *                  if this element matches the predicate, eg. has a specific ID.
	 * @param <T> type of elements in Iterable
	 * @return An optional that contains the first element in <pre>it</pre> that matches <pre>predicate</pre>
	 *         or Optional.empty() if no element in the collections matches that predicate.
	 */
	public static <T> Optional<T> find(Iterable<T> it, Predicate<T> predicate) {
		for(T elem : it) {
			if (predicate.test(elem))
				return Optional.of(elem);
		}
		return Optional.empty();
	}


  //http://stackoverflow.com/questions/309424/read-convert-an-inputstream-to-a-string
  public static String stream2String(InputStream inputStream) throws IOException {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    int length;
    while ((length = inputStream.read(buffer)) != -1) {
      result.write(buffer, 0, length);
    }
    try {
      return result.toString("UTF-8");
    } catch (UnsupportedEncodingException e) {
      //You should really know "UTF-8" :-)
      return "";  // yeah ... i know
    }
  }

  /** @return a Data n days ago */
  public static Date daysAgo(long days) {  //BUGFIX: MUST calculate in long!
    return new Date(System.currentTimeMillis() - days * 3600*24*1000);
  }

  /** add or subtract n days from the given date */
  public static Date addDays(Date date, int days)
  {
    Calendar cal = Calendar.getInstance();
    cal.setTime(date);
    cal.add(Calendar.DATE, days); //negative number of days is possible
    return cal.getTime();
  }

  static final Random rand = new Random();
  /** There is deliberately no number 0/O/o 1/I/i/j/l/L this array, because they can be confused so easily in many fonts. */
  private static final char[] EASY_CHARS = "234567890ABCDEFGHKLMNPQRSTUVWXYZabcdefghkmnpqrstuvwxyz".toCharArray();

  /**
   * Generate some random characters that can be used for human readable tokens.
   * @param len number of chars to generate
   * @return a String of length len with "easy" random characters and numbers
   */
  public static String easyToken(int len) {
    StringBuffer buf = new StringBuffer();
    for (int i = 0; i < len; i++) {
      buf.append(EASY_CHARS[rand.nextInt(EASY_CHARS.length)]);
    }
    return buf.toString();
  }

  /**
   * Create a string that consists of random digits [0-9] of that length. Can be used as validation token.
   * @param len number of digits to produce
   * @return a string of length "len" that consists of random digits
   */
  public static String randomDigits(int len) {         // Example: len = 3
    long max = (long) Math.pow(len, 10);                // 10^3  = 1000
    long min = (long) Math.pow(len-1, 10);              // 10^2  =  100
    long number = min + (Math.abs(rand.nextLong()) % (max-min));  // 100 + [0...899]  = [100...999]
    return String.valueOf(number);
  }



  private static ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);

  /**
   * convert a long to byte array.
   * (Used for hashing with MessageDigest.)
   * @param x any long value
   * @return the long as a byte array
   */
  public static byte[] longToBytes(long x) {
    buffer.putLong(0, x);
    return buffer.array();
  }

  /**
   * convert a byte array into its HEX representation as String
   * @param byteData
   * @return
   */
  public static String bytesToString(byte[] byteData) {
    //convert the byte to hex format method 2
    StringBuffer hexString = new StringBuffer();
    for (int i=0;i<byteData.length;i++) {
      String hex=Integer.toHexString(0xff & byteData[i]);
      if(hex.length()==1) hexString.append('0');
      hexString.append(hex);
    }
    return hexString.toString();
  }

	/**
	 * Print a tree structure in a pretty ASCII fromat.
	 *
	 * I LOVE stackoverflow :-)  https://stackoverflow.com/questions/4965335/how-to-print-binary-tree-diagram
	 *
	 * @param node The current node. Pass the root node of your tree in initial call.
	 * @param getChildrenFunc A {@link Function} that returns the children of a given node.
	 * @param <T> The type of your nodes. Anything that has a toString can be used.
	 */
	public static <T> void printTreeRec(T node, Function<T, List<T>> getChildrenFunc) {
		if (getChildrenFunc == null) throw new IllegalArgumentException("need getChildrenFunc to printTreeRec");
		if (node == null) return;
		BiConsumer printer = (prefix, n) -> System.out.println(prefix + n.toString());
		printTreeRec("", node, printer, getChildrenFunc, false);
	}

	/**
	 * Recursively print a tree structure
	 * @param indent the current indentation with ASCII art branch
	 * @param node the current node (of type T)
	 * @param printer This function is used to print. It will get two parameters: The prefix to print and the current node
	 * @param getChildrenFunc Must return  the list of child nodes for a give node
	 * @param isTail true if node is the last leaf of its parent
	 * @param <T> type of nodes. Nodes may be anything. The only requirement is that the printer function can print the node.
	 */
	public static <T> void printTreeRec(String indent, T node, BiConsumer<String, T> printer, Function<T, List<T>> getChildrenFunc, boolean isTail) {
		// We could also apply some FUNCTIONAL programming wizardry: Skip the first parameter "indent". Instead curry the printer function in each recursion level *G*
		String nodeConnection = isTail ? "└─ " : "├─ ";
		printer.accept(indent + nodeConnection, node);		// print the current node with that prefix
		List<T> children = getChildrenFunc.apply(node);
		for (int i = 0; i < children.size(); i++) {
			String newPrefix = indent + (isTail ? "   " : "│  ");
			printTreeRec(newPrefix, children.get(i), printer, getChildrenFunc, i == children.size()-1);
		}
	}

	/**
	 * Clean mobile phone number: Replace everything except plus('+') and number (0-9).
	 * Specifically spaces will be removed.
	 * This is a very simple thing. Have a look at google phone lib for sophisticated phone number parsing
	 * @param mobile a non formatted phone numer
	 * @return the cleaned up phone number
	 */
	public static String cleanMobilephone(String mobile) {
		if (mobile == null) return null;
		return mobile.replaceAll("[^\\+0-9]", "");
	}

	/**
	 * emails a case IN-sensitive. So store and compare them in lowercase
	 * @param email an email address
	 * @return the email in lowercase
	 */
	public static String cleanEmail(String email) {
		if (email == null) return null;
		return email.toLowerCase().trim();
	}
}