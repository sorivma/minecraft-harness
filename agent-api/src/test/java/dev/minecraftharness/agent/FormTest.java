package dev.minecraftharness.agent;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class FormTest {
    @Test
    public void rejectsMissingUnknownAndInvalidChoicesWithoutCoercion() {
        Form form =
                new Form(Arrays.asList(Form.Field.choice("decision", "Decision", "allow", "deny")));
        assertEquals(
                "Required", form.validate(Collections.<String, Value>emptyMap()).get("decision"));
        assertFalse(
                form.validate(Collections.singletonMap("decision", Value.bool(true))).isEmpty());
        assertFalse(
                form.validate(Collections.singletonMap("decision", Value.text("maybe"))).isEmpty());
        assertTrue(
                form.validate(Collections.singletonMap("decision", Value.text("deny"))).isEmpty());
        assertFalse(form.validate(Collections.singletonMap("other", Value.text("deny"))).isEmpty());
    }

    @Test
    public void nestedValidationReportsFieldPath() {
        Form child = new Form(Arrays.asList(Form.Field.text("name", "Name", true)));
        Form form =
                new Form(
                        Arrays.asList(
                                new Form.Field(
                                        "person",
                                        "Person",
                                        "",
                                        Form.Type.GROUP,
                                        true,
                                        Collections.<String>emptyList(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        child)));
        assertTrue(
                form.validate(
                                Collections.singletonMap(
                                        "person",
                                        Value.object(Collections.<String, Value>emptyMap())))
                        .containsKey("person.name"));
    }
}
