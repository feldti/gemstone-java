package net.gs64.gci;

/**
 * A signal another session sent with {@link GciSession#sendSignal}.
 *
 * @param senderSerial serial number of the sending session (unique, unlike session numbers)
 * @param senderSessionId session number of the sender, or null if it has logged out meanwhile
 *     (GemStone 3.6 may still answer the old number for a while); session numbers are reused
 * @param value the number sent with the signal
 * @param message the text sent with the signal
 */
public record GciSessionSignal(long senderSerial, Long senderSessionId, long value, String message) {
}
