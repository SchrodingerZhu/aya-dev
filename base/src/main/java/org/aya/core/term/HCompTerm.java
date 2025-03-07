// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.core.term;

import org.jetbrains.annotations.NotNull;

public record HCompTerm(@NotNull Term type, @NotNull Term phi, @NotNull Term u, @NotNull Term u0) implements Term {}
