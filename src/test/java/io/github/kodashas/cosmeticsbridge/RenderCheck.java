package io.github.kodashas.cosmeticsbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

/**
 * Self-check for message rendering, the only piece of this plugin that can be exercised
 * without a running server. Run it with assertions on:
 *
 * <pre>
 * mvn test-compile
 * java -ea -cp "target/test-classes;target/classes;libs/paper-api.jar" \
 *     io.github.kodashas.cosmeticsbridge.RenderCheck
 * </pre>
 *
 * <p>Prints "RenderCheck: ok" and exits 0 when everything holds.
 */
public final class RenderCheck {

    public static void main(String[] args) {
        // A blank template means "send nothing".
        expect(ExcellentCurrencyEconomyProvider.render(null, 1, "coins") == null,
                "null template should render nothing");
        expect(ExcellentCurrencyEconomyProvider.render("", 1, "coins") == null,
                "empty template should render nothing");

        // Both placeholders are substituted.
        String text = plain(ExcellentCurrencyEconomyProvider.render(
                "<green>Paid <amount> <currency>.", 25, "tokens"));
        expect("Paid 25 tokens.".equals(text), "placeholders not substituted: " + text);

        // A template using neither placeholder still renders.
        expect("Done.".equals(plain(ExcellentCurrencyEconomyProvider.render("Done.", 1, "coins"))),
                "plain template did not render");

        // A currency name containing markup is inserted as text, not parsed as a tag.
        String injected = plain(ExcellentCurrencyEconomyProvider.render(
                "<amount> <currency>", 5, "<red>gems"));
        expect("5 <red>gems".equals(injected), "currency name was parsed as markup: " + injected);

        System.out.println("RenderCheck: ok");
    }

    /** Flattens a component tree to its text, so the check needs no serializer dependency. */
    private static String plain(Component component) {
        StringBuilder out = new StringBuilder();
        flatten(component, out);
        return out.toString();
    }

    private static void flatten(Component component, StringBuilder out) {
        if (component instanceof TextComponent text) {
            out.append(text.content());
        }
        for (Component child : component.children()) {
            flatten(child, out);
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
