package net.gs64.gci.mapping;

import java.util.List;

/** Mapping errors: either the mapping does not fit the image, or a value does not fit its field. */
public class GsMappingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String[] problems;

    public GsMappingException(String message, List<String> problems) {
        super(message);
        this.problems = problems.toArray(String[]::new);
    }

    public List<String> problems() {
        return List.of(problems);
    }

    static GsMappingException of(List<String> problems) {
        return new GsMappingException(problems.size() == 1 ? problems.get(0)
                : problems.size() + " mapping problems:" + System.lineSeparator() + "  " + String.join(System.lineSeparator() + "  ", problems), problems);
    }

    static GsMappingException of(String problem) {
        return of(List.of(problem));
    }
}
