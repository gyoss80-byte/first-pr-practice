package com.voiceprompter

import com.voiceprompter.tracker.ScriptTracker

/**
 * Word lists for "Listen for script words": the script's own words, plus how numbers are
 * said and the most common words of the language, so ad-libs are still heard as ordinary
 * speech instead of being forced onto script words.
 */
object Vocabulary {
    fun forScript(script: Script): Set<String> =
        ScriptTracker.vocabulary(script.text) + numberWords(script.lang) + commonWords(script.lang)

    private fun numberWords(lang: Lang): List<String> = when (lang) {
        Lang.EN -> """
            zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen
            fifteen sixteen seventeen eighteen nineteen twenty thirty forty fifty sixty seventy
            eighty ninety hundred thousand million billion trillion point and dollars dollar
            cents cent percent
        """
        Lang.ES -> """
            cero uno dos tres cuatro cinco seis siete ocho nueve diez once doce trece catorce
            quince dieciséis diecisiete dieciocho diecinueve veinte veintiuno veintidós veintitrés
            veinticuatro veinticinco veintiséis veintisiete veintiocho veintinueve treinta
            cuarenta cincuenta sesenta setenta ochenta noventa cien ciento doscientos trescientos
            cuatrocientos quinientos seiscientos setecientos ochocientos novecientos mil millón
            millones y con punto coma por dólares dólar centavos centavo
        """
    }.split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun commonWords(lang: Lang): List<String> = when (lang) {
        Lang.EN -> """
            the be to of and a in that have i it for not on with he as you do at this but his by
            from they we say her she or an will my one all would there their what so up out if
            about who get which go me when make can like time no just him know take people into
            year your good some could them see other than then now look only come its over think
            also back after use two how our work first well way even new want because any these
            give day most us is are was were been has had did does got going gonna okay ok yeah
            yes right really very here let's don't i'm it's that's you're we're can't
            um uh so actually basically thing things lot little much more
        """
        Lang.ES -> """
            de la que el en y a los se del las un por con no una su para es al lo como más pero
            sus le ya o este sí porque esta entre cuando muy sin sobre también me hasta hay donde
            quien desde todo nos durante todos uno les ni contra otros ese eso ante ellos e esto
            mí antes algunos qué unos yo otro otras otra él tanto esa estos mucho quienes nada
            muchos cual poco ella estar estas algunas algo nosotros mi mis tú te ti tu tus bueno
            entonces pues vale okay o sea verdad aquí ahora así bien hacer tener ir ver decir
            voy vamos puedo puede tiene hay está están es son fue era cosa cosas
        """
    }.split(Regex("\\s+")).filter { it.isNotEmpty() }
}
