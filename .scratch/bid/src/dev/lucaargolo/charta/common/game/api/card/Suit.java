package dev.lucaargolo.charta.common.game.api.card;

/** Stand-in for Charta's registered Suit: only identity and the name are used by the bidding. */
public final class Suit {

    private final String name;

    public Suit(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
