package dev.msgforge.instance;

/**
 * O nome do perfil da conta chegou ou mudou depois do login (numa conta comum ele vem pela
 * sincronização, não no login). Evento interno: grava o nome e atualiza o Manager, sem sair para
 * webhook nem RabbitMQ.
 */
public record ProfileNameChanged(String instanceName, String profileName) {}
