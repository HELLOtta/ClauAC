package io.github.hellotta.clauac.simulation.world;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.RecipeAccess;
import net.minecraft.world.item.crafting.RecipePropertySet;
import net.minecraft.world.item.crafting.SelectableRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;

// - The client fills its recipe access from recipe packets for its crafting screens; movement never reads recipes, so -
// - the sandbox does not track those packets and answers with the empty sets the protocol itself uses for "none" -
final class EmptyRecipeAccess implements RecipeAccess {

    static final EmptyRecipeAccess INSTANCE = new EmptyRecipeAccess();

    private EmptyRecipeAccess() {
    }

    @Override
    public RecipePropertySet propertySet(ResourceKey<RecipePropertySet> key) {
        return RecipePropertySet.EMPTY;
    }

    @Override
    public SelectableRecipe.SingleInputSet<StonecutterRecipe> stonecutterRecipes() {
        return SelectableRecipe.SingleInputSet.empty();
    }
}
