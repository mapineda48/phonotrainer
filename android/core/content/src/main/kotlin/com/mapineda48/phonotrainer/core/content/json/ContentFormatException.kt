package com.mapineda48.phonotrainer.core.content.json

/** A content file that decodes as JSON but breaks a rule of its format (another schema, a value outside a closed set).
 *  The message names only a JSON path and the reason, never the content itself. */
internal class ContentFormatException(message: String) : IllegalArgumentException(message)
