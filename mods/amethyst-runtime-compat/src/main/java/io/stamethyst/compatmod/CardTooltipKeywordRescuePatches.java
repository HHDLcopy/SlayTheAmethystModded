package io.stamethyst.compatmod;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.ByRef;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.helpers.TipHelper;

import java.util.ArrayList;

public final class CardTooltipKeywordRescuePatches {
    private CardTooltipKeywordRescuePatches() {
    }

    @SpirePatch(
        clz = TipHelper.class,
        method = "renderTipForCard",
        paramtypez = {AbstractCard.class, SpriteBatch.class, ArrayList.class}
    )
    public static class TipHelperRenderTipForCardPatch {
        @SpirePrefixPatch
        public static void Prefix(
            AbstractCard card,
            SpriteBatch spriteBatch,
            @ByRef ArrayList<String>[] keywords
        ) {
            if (!CompatRuntimeState.isCardTooltipKeywordRescueEnabled()) {
                return;
            }
            if (keywords == null || keywords.length == 0) {
                return;
            }
            ArrayList<String> originalKeywords = keywords[0];
            if (originalKeywords == null || !originalKeywords.contains(null)) {
                return;
            }

            ArrayList<String> filteredKeywords = new ArrayList<String>(originalKeywords.size());
            int removed = 0;
            for (String keyword : originalKeywords) {
                if (keyword == null) {
                    removed++;
                } else {
                    filteredKeywords.add(keyword);
                }
            }
            keywords[0] = filteredKeywords;
            RoomStateRescueNoticeBridge.notifyRescue(
                "card_tooltip_keyword_render",
                "Filtered "
                    + removed
                    + " null card tooltip keyword(s) for "
                    + describeCard(card)
            );
        }
    }

    private static String describeCard(AbstractCard card) {
        if (card == null) {
            return "<null>";
        }
        if (card.cardID != null) {
            return card.cardID;
        }
        if (card.name != null) {
            return card.name;
        }
        return card.getClass().getName();
    }
}
