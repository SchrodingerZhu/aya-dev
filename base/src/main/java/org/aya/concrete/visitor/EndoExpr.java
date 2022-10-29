// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.concrete.visitor;

import org.aya.concrete.Expr;
import org.jetbrains.annotations.NotNull;

import java.util.function.UnaryOperator;

public interface EndoExpr extends UnaryOperator<Expr>, EndoPattern {
  default @NotNull Expr pre(@NotNull Expr expr) {
    return expr;
  }

  default @NotNull Expr post(@NotNull Expr expr) {
    return expr;
  }

  default @NotNull Expr apply(@NotNull Expr expr) {
    return post(switch (pre(expr)) {
      case Expr.Match match -> match.descent(this, this::apply);
      case Expr e -> e.descent(this);
    });
  }
}
