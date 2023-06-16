package org.liquido.poll.converter;

import org.liquido.vote.Matrix;

import javax.persistence.AttributeConverter;

/**
 * Converter for 2D LongMatrix from/to Json String
 * This is used to store the duelMatrix in a {@link org.liquido.poll.PollEntity}
 */
public class MatrixConverter implements AttributeConverter<Matrix, String> {

	@Override
	public String convertToDatabaseColumn(Matrix matrix) {
		if (matrix == null) return "";
		return matrix.toJsonValue();
	}

	@Override
	public Matrix convertToEntityAttribute(String json) {
		if (json == null || json.equals("")) return new Matrix(0,0);
		return Matrix.fromJsonValue(json);
	}
}