package net.gs64.gci.mapping;

import net.gs64.gci.GsGeneratedMappings;

/** The same tests with the metadata of the annotation processor instead of reflection. */
class GsMappingGeneratedIntegrationTest extends GsMappingIntegrationTest {
    @Override
    GsObjectContext newContext() {
        return new GsObjectContext(s(), GsGeneratedMappings.DEFAULT);
    }
}
