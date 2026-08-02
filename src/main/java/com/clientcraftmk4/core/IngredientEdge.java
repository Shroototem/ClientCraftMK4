package com.clientcraftmk4.core;

import java.util.List;

public record IngredientEdge(
        int count,
        List<IngredientOption> options
) {}
