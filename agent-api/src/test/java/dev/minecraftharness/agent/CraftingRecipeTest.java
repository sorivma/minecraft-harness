package dev.minecraftharness.agent;

import static org.junit.Assert.*;

import org.junit.Test;

public class CraftingRecipeTest {
    private String recipe() {
        return "Arrange planks in a square.\n```minecraft-recipe\nCrafting table\nminecraft:crafting_table|Crafting table|1|0\n"
                + "minecraft:planks|Oak planks|1|0\nminecraft:planks|Oak planks|1|0\n-\n"
                + "minecraft:planks|Oak planks|1|0\nminecraft:planks|Oak planks|1|0\n-\n-\n-\n-\n```\nUse your inventory grid.";
    }

    @Test
    public void preservesPlacementAndSurroundingExplanation() {
        CraftingRecipe r = CraftingRecipe.parse(recipe().replace("\n", "\r\n"));
        assertNotNull(r);
        assertEquals(9, r.slots.size());
        assertNull(r.slots.get(2));
        assertEquals("minecraft:planks", r.slots.get(4).id);
        assertEquals("minecraft:crafting_table", r.output.id);
        assertTrue(r.prose.contains("Arrange planks"));
        assertTrue(r.prose.contains("Use your inventory"));
        assertTrue(r.explanation().contains("Row 2, column 2: 1 x Oak planks"));
    }

    @Test
    public void invalidAndStreamingBlocksFallBackToText() {
        assertNull(CraftingRecipe.parse(recipe().replace("|1|0", "|0|0")));
        assertNull(CraftingRecipe.parse(recipe().replace("|1|0", "|1|-1")));
        assertNull(CraftingRecipe.parse(recipe().replace("\n```\nUse your inventory grid.", "")));
        assertNull(CraftingRecipe.parse(recipe().replace("\n-\n-\n-\n```", "\n-\n-\n```")));
        assertNull(CraftingRecipe.parse("ordinary text"));
    }
}
