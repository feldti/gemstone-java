package net.gs64.gci.mapping;

import net.gs64.gci.GsGeneratedMappings;

/** The same tests with the metadata of the annotation processor instead of reflection. */
class GsSaveChangesGeneratedIntegrationTest extends GsSaveChangesIntegrationTest {
    @Override
    GsObjectContext newContext() {
        return new GsObjectContext(s(), GsGeneratedMappings.DEFAULT);
    }
}
