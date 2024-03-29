package de.verdox.vpipeline.api.messaging;

import de.verdox.vpipeline.api.NetworkParticipant;
import de.verdox.vpipeline.api.messaging.instruction.Instruction;
import de.verdox.vpipeline.api.messaging.instruction.ResponseCollector;
import de.verdox.vpipeline.impl.messaging.ResponseCollectorImpl;
import de.verdox.vpipeline.api.pipeline.core.SystemPart;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * @version 1.0
 * @Author: Lukas Jonsson (Verdox)
 * @date 18.06.2022 23:10
 */
public interface MessagingService extends SystemPart {

    NetworkParticipant getNetworkParticipant();
    Set<RemoteMessageReceiver> getRemoteMessageReceivers();
    void sendKeepAlivePing();
    Transmitter getTransmitter();
    UUID getSessionUUID();
    String getSessionIdentifier();
    MessageFactory getMessageFactory();

    void postMessageEvent(String channel, Instruction<?> instruction);

    <R, T extends Instruction<R>> ResponseCollector<R> sendInstruction(@NotNull T instruction, UUID... receivers);
    <R, T extends Instruction<R>> ResponseCollector<R> sendInstruction(@NotNull Class<? extends T> instructionType, Consumer<T> consumer, UUID... receivers);
}