/**
 * Full-text search over extracted document content incl. OCR (PRD DOC-06), backed by OpenSearch. Owns the index and
 * its mapping; authorization stays with the caller (the data room passes the folders a user may see).
 */
@ApplicationModule(displayName = "Search", allowedDependencies = "shared")
package com.inpartnerhub.diligence.search;

import org.springframework.modulith.ApplicationModule;
