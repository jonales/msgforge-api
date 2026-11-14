package dev.msgforge.notification;

/**
 * Um e-mail já montado, como fica gravado na outbox (ADR 0012): o conteúdo vai inteiro, para a
 * entrega não depender de template nem de dado que mude depois. Credencial do SMTP nunca entra
 * aqui; o remetente ({@code SMTP_FROM}) também é lido na hora da entrega.
 *
 * @param to      destinatário (um endereço só)
 * @param subject assunto
 * @param text    versão texto
 * @param html    versão HTML
 */
public record EmailMessage(String to, String subject, String text, String html) {}
