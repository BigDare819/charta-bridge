package dev.lucaargolo.charta.common.game.api.card;

/** A card is a suit and a rank, nothing else, as far as the bidding is concerned. */
public record Card(Suit suit, Rank rank) {

    @Override
    public String toString() {
        return rank + "" + suit;
    }
}
