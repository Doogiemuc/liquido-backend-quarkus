package org.liquido.model;

import org.hibernate.annotations.ValueGenerationType;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@ValueGenerationType(generatedBy = LoggedInUserGenerator.class)
@Retention(RUNTIME) @Target({METHOD,FIELD})
public @interface CreatedBy {
}