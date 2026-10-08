package dev.lucaargolo.charta.common.game.api;

import dev.lucaargolo.charta.common.game.api.card.Card;

import java.util.List;

/** A player with a hand. That is all the bidding ever looks at. */
public abstract class CardPlayer {

    public abstract List<Card> hand();
}
