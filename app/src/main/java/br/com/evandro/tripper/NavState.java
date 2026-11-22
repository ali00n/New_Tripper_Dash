package br.com.evandro.tripper;

import java.util.Objects;

/** Um passo de navegação já traduzido para o painel. */
final class NavState {
    final int maneuver;
    final int roundaboutExit;
    final int metersToTurn;
    final int metersLeft;
    final String eta;
    final String remaining;
    final String street;

    NavState(int maneuver, int roundaboutExit, int metersToTurn, int metersLeft, String eta, String remaining, String street) {
        this.maneuver = maneuver;
        this.roundaboutExit = roundaboutExit;
        this.metersToTurn = metersToTurn;
        this.metersLeft = metersLeft;
        this.eta = eta;
        this.remaining = remaining;
        this.street = street;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof NavState)) return false;
        NavState n = (NavState) o;
        return maneuver == n.maneuver && roundaboutExit == n.roundaboutExit && metersToTurn == n.metersToTurn
                && metersLeft == n.metersLeft && Objects.equals(eta, n.eta) && Objects.equals(remaining, n.remaining)
                && Objects.equals(street, n.street);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maneuver, roundaboutExit, metersToTurn, metersLeft, eta, remaining, street);
    }

    @Override
    public String toString() {
        return "manobra=" + maneuver + (roundaboutExit != 255 ? " saída=" + roundaboutExit : "")
                + " dist=" + metersToTurn + "m resta=" + metersLeft + "m chegada=" + eta
                + " tempo=" + remaining + " rua=" + street;
    }
}
