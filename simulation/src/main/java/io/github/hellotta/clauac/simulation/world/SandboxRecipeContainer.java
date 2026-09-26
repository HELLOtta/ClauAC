package io.github.hellotta.clauac.simulation.world;

import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.RecipeAccess;
import net.minecraft.world.item.crafting.RecipePropertySet;
import net.minecraft.world.item.crafting.SelectableRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;

// - Port of the client-only ClientRecipeContainer: the part of the server's recipes the client receives with -
// - ClientboundUpdateRecipesPacket. Menus read it on the client to decide which items a slot accepts and where a -
// - shift click moves them (furnaces, brewing stands, smithing tables) and which recipes a stonecutter offers -
public final class SandboxRecipeContainer implements RecipeAccess {

    // - What the client holds before the server sent any recipes -
    public static final SandboxRecipeContainer NONE = new SandboxRecipeContainer(Map.of(), SelectableRecipe.SingleInputSet.empty());

    private final Map<ResourceKey<RecipePropertySet>, RecipePropertySet> itemSets;
    private final SelectableRecipe.SingleInputSet<StonecutterRecipe> stonecutterRecipes;

    public SandboxRecipeContainer(
            Map<ResourceKey<RecipePropertySet>, RecipePropertySet> itemSets, SelectableRecipe.SingleInputSet<StonecutterRecipe> stonecutterRecipes
    ) {
        this.itemSets = itemSets;
        this.stonecutterRecipes = stonecutterRecipes;
    }

    @Override
    public RecipePropertySet propertySet(ResourceKey<RecipePropertySet> id) {
        return this.itemSets.getOrDefault(id, RecipePropertySet.EMPTY);
    }

    @Override
    public SelectableRecipe.SingleInputSet<StonecutterRecipe> stonecutterRecipes() {
        return this.stonecutterRecipes;
    }
}
