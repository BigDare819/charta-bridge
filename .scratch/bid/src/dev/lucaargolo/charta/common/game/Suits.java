package dev.lucaargolo.charta.common.game;

import dev.lucaargolo.charta.common.game.api.card.Suit;

import java.util.List;

/** Suits, in the order the bidding walks them. */
public final class Suits {

    public static final Suit SPADES = new Suit("spades");
    public static final Suit HEARTS = new Suit("hearts");
    public static final Suit CLUBS = new Suit("clubs");
    public static final Suit DIAMONDS = new Suit("diamonds");

    public static final List<Suit> STANDARD = List.of(SPADES, HEARTS, CLUBS, DIAMONDS);

    private Suits() {
    }

    public static Location getLocation(Suit suit) {
        return new Location(suit.name());
    }

    /** Stands in for the resource location, whose path is what the strain mapping reads. */
    public static final class Location {

        private final String path;

        private Location(String path) {
            this.path = path;
        }

        public String getPath() {
            return path;
        }
    }
}
