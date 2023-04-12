package org.liquido.util;

import io.quarkus.test.junit.QuarkusTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@QuarkusTest
public class LsonTests {

	@Test
	public void putAndGetValueUnderPathTest() throws Exception {

		String dummyValue = "dummyValue";

		Lson l = new Lson();
		l.put("parent.child.attribute", dummyValue);
		Object res = l.get("parent.child.attribute");

		assertTrue(res instanceof String);

		String str = (String)res;
		assertEquals(dummyValue, str, "Expected to have " + dummyValue + " in Lson");
	}



}
