package com.example.platform.search;

import java.util.Map;
import java.util.UUID;

/** Port for indexing documents into the search engine. */
public interface SearchIndex {

    void index(UUID documentId, String content, Map<String, Object> attributes);
}
