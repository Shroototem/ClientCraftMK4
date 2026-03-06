package com.clientcraftmk4.tree;

import java.util.List;

public record IngredientEdge(
        int count,
        List<IngredientOption> options
) {}
