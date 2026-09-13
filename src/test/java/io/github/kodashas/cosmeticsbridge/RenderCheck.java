package io.github.kodashas.cosmeticsbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

/**
 * Self-check for message rendering, the only piece of this plugin that runs without a server.
 * Run it with {@code scripts/check.sh}, which compiles it and enables assertions.
 */
public final class RenderCheck {

    public static void main(String[] args) {
        // A blank template means "send nothing".
        assert ExcellentCurrencyEconomyProvider.render(null, 1, "coins") == null
                : "null template should render nothing";
        assert ExcellentCurrencyEconomyProvider.render("", 1, "coins") == null
                : "empty template should render nothing";

        // Both placeholders are substituted.
        String text = plain(ExcellentCurrencyEconomyProvider.render(
                "<green>Paid <amount> <currency>.", 25, "tokens"));
        assert "Paid 25 tokens.".equals(text) : "placeholders not substituted: " + text;

        // A template using neither placeholder still renders.
        String plain = plain(ExcellentCurrencyEconomyProvider.render("Done.", 1, "coins"));
        assert "Done.".equals(plain) : "plain template did not render: " + plain;

        // A currency name containing markup is inserted as text, not parsed as a tag.
        String injected = plain(ExcellentCurrencyEconomyProvider.render(
                "<amount> <currency>", 5, "<red>gems"));
        assert "5 <red>gems".equals(injected) : "currency name was parsed as markup: " + injected;

        System.out.println("RenderCheck: ok");
    }

    /** Flattens a component tree to its text, so the check needs no serializer dependency. */
    private static String plain(Component component) {
        String text = component instanceof TextComponent content ? content.content() : "";
        for (Component child : component.children()) {
            text += plain(child);
        }
        return text;
    }
}
