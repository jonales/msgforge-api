/**
 * <b>Shim</b>, não é a biblioteca da Aspose.
 *
 * <p>O Cobalt 0.0.10 depende de {@code com.aspose:aspose-words}, que é comercial e não está no
 * Maven Central, então a dependência é excluída no {@code pom.xml}. A classe
 * {@code it.auties.whatsapp.util.Medias} referencia estes tipos, e a JVM precisa carregá-los
 * para verificar a classe; sem eles, montar <i>qualquer</i> mensagem de mídia falha com
 * {@code NoClassDefFoundError}.
 *
 * <p>Estas classes só existem para a verificação passar. Todo método lança
 * {@link UnsupportedOperationException}; o Cobalt captura {@code Throwable} nesses pontos e usa
 * o padrão: documentos saem com 1 página e sem miniatura. Nenhum código da Aspose é usado ou
 * distribuído. Se trocar de versão do Cobalt, confira as referências com
 * {@code javap -c -p it.auties.whatsapp.util.Medias | grep com/aspose} (ADR 0007).
 */
package com.aspose.words;
