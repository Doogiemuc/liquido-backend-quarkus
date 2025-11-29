package org.liquido.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.liquido.util.LiquidoException;

import java.io.File;
import java.io.IOException;

/**
 * This little tool creates LiquidoException codes for the JavaScript/VUE frontend
 * from the codes defined in LiquidoException.Errors.
 * Frontend and backend codes must match!
 */
public class LiquidoExceptionJsonGenerator {

	private static final String OUTPUT_PATH = "LiquidoExceptionCodes.js";

	public static void main(String[] args) throws IOException {
		ObjectMapper mapper = new ObjectMapper();
		ObjectNode root = mapper.createObjectNode();

		for (LiquidoException.Errors error : LiquidoException.Errors.values()) {

			root.put(error.name(), error.getLiquidoErrorCode());
		}

		String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);

		// Wrap it in a const export for ES6/Vue
		String fileContent = "/* LiquidoException codes from our LIQUIDO backend. This file is auto generated! */\n\nexport default " + json + ";";

		File file = new File(OUTPUT_PATH);
		mapper.writeValue(file, fileContent);

		// Simple File Writer approach ensures valid JS syntax:
		java.nio.file.Files.writeString(
				java.nio.file.Path.of(OUTPUT_PATH),
				fileContent
		);

		System.out.println("Generated LiquidoException codes at " + OUTPUT_PATH);
	}
}