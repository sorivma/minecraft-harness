package dev.minecraftharness.chat.widget;

import dev.minecraftharness.agent.CraftingRecipe;

/** Built-in widget uses exactly the same extension interface as other mods. */
final class RecipeWidget implements Widget {
    private final CraftingRecipe recipe;

    private RecipeWidget(CraftingRecipe recipe) {
        this.recipe = recipe;
    }

    static String instructions() {
        return CraftingRecipe.INSTRUCTIONS
                .replace("minecraft-recipe", "minecraftharness:crafting-v1")
                .replace("Use one block per response. ", "");
    }

    static Widget parse(String payload) {
        CraftingRecipe recipe = CraftingRecipe.parse("```minecraft-recipe\n" + payload + "\n```");
        return recipe == null ? null : new RecipeWidget(recipe);
    }

    public String title() {
        return "Crafting: " + recipe.title;
    }

    public String plainText() {
        return recipe.explanation();
    }

    public int height(int width) {
        return width >= 90 ? 64 : 0;
    }

    public void render(WidgetCanvas canvas) {
        for (int i = 0; i < 9; i++)
            slot(canvas, recipe.slots.get(i), (i % 3) * 20, (i / 3) * 20, i + 1);
        canvas.text(">", 62, 24, 0xff8fc9ff);
        slot(canvas, recipe.output, 72, 20, 0);
    }

    private void slot(WidgetCanvas canvas, CraftingRecipe.Stack stack, int x, int y, int index) {
        // Recessed inventory-style slots stay visible against both chat and overlay backgrounds.
        canvas.rectangle(x, y, 18, 18, 0xff373737);
        canvas.rectangle(x + 1, y + 1, 17, 17, 0xffeeeeee);
        canvas.rectangle(x + 1, y + 1, 16, 16, 0xff8b8b8b);
        if (stack != null && !canvas.item(stack.id, stack.metadata, stack.count, x + 1, y + 1))
            canvas.text(index == 0 ? "Out" : Integer.toString(index), x + 2, y + 4, 0xff8fc9ff);
    }
}
