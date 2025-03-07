// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.pretty.style;

import org.aya.pretty.doc.Style;

/**
 * The key syntax:
 * <pre>
 *   key ::= (key '::') key
 *         | [A-Za-z_][A-Za-z0-9_]*
 * </pre>
 * The '::' is used to indicate scopes in some backends (as some backends do not have scopes).
 * For example, we will use `::` to distinguish nested "class" attributes in the HTML backend.
 *
 * @see org.aya.pretty.backend.html.Html5Stylist#styleKeyToCss(String)
 */
public enum AyaStyleKey {
  Keyword("Aya::Keyword"),
  Fn("Aya::Fn"),
  Prim("Aya::Primitive"),
  Data("Aya::Data"),
  Con("Aya::Constructor"),
  Struct("Aya::Struct"),
  Field("Aya::Field"),
  Generalized("Aya::Generalized");

  private final String key;

  AyaStyleKey(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  public Style preset() {
    return Style.preset(key());
  }
}
