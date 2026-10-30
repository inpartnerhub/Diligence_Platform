/**
 * Shared kernel: cross-cutting types (errors, identifiers, web conventions) that every module may depend on.
 * Keep this module small; business concepts never belong here.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN, displayName = "Shared Kernel")
package com.inpartnerhub.diligence.shared;

import org.springframework.modulith.ApplicationModule;
