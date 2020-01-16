package uk.gov.justice.hmpps.fmb

import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.WorkbookFactory
import uk.gov.justice.hmpps.fmb.SpreadsheetProcessor.instructionToSql
import uk.gov.justice.hmpps.fmb.SpreadsheetProcessor.instructionsForLdu
import uk.gov.justice.hmpps.fmb.SpreadsheetProcessor.readSheets
import uk.gov.justice.hmpps.fmb.SpreadsheetProcessor.teamsByLdu
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

const val fileName = "src/main/resources/confirmed nps crc mailboxes.xlsx"

val sheetNames = sequenceOf(
        "Confirmed NE CRC FMBs",
        "Confirmed NE NPS FMBs",
        "Confirmed NW CRC FMBs",
        "Confirmed NW NPS FMBs")

data class MailboxSpec(val probationAreaCode: String, val localDeliveryUnitCode: String, val teamCode: String, val functionalMailbox: String)
data class Ldu(val probationAreaCode: String, val localDeliveryUnitCode: String)
data class Team(val teamCode: String, val functionalMailbox: String)

interface Instruction
data class InsertLdu(val ldu: Ldu) : Instruction
data class InsertLduWithFmb(val ldu: Ldu, val functionalMailbox: String) : Instruction
data class InsertTeamWithFmb(val ldu: Ldu, val team: Team) : Instruction

object SpreadsheetProcessor {
    fun readSheets(fileName: String, sheetNames: Sequence<String>): Sequence<MailboxSpec> =
            sheetNames.flatMap { readSheetRows(FileInputStream(fileName), it) }

    fun teamsByLdu(mailboxSpecs: Sequence<MailboxSpec>): Map<Ldu, List<Team>> = mailboxSpecs
            .filterNot { spec ->
                spec.probationAreaCode.isEmpty() ||
                        spec.localDeliveryUnitCode.isEmpty() ||
                        spec.teamCode.isEmpty()
            }
            .groupBy(
                    { mbs -> Ldu(mbs.probationAreaCode, mbs.localDeliveryUnitCode) },
                    { mbs -> Team(mbs.teamCode, mbs.functionalMailbox) }
            )

    fun instructionsForLdu(ldu: Ldu, teams: List<Team>): List<Instruction> {
        val teamFunctionalMailboxes = toTeamFunctionalMailboxes(teams)
        return when (teamFunctionalMailboxes.size) {
            0 -> listOf()
            1 -> lduWithSingleFmb(ldu, teamFunctionalMailboxes.first())
            else -> lduWithMultipleFmbs(ldu, teams)
        }
    }

    private fun toTeamFunctionalMailboxes(teams: List<Team>): Set<String> = teams
            .map { it.functionalMailbox.toLowerCase() }
            .filter { it.contains('@') }
            .toSet()

    private fun lduWithSingleFmb(ldu: Ldu, functionalMailbox: String): List<Instruction> = listOf(InsertLduWithFmb(ldu, functionalMailbox))

    private fun lduWithMultipleFmbs(ldu: Ldu, teams: List<Team>): List<Instruction> = listOf(InsertLdu(ldu)) + instructionsForTeams(ldu, teams)

    private fun instructionsForTeams(ldu: Ldu, teams: List<Team>): List<Instruction> = teams
            .filter { team -> team.functionalMailbox.contains('@') }
            .map { team -> InsertTeamWithFmb(ldu, team) }

    fun instructionToSql(instruction: Instruction) = when (instruction) {
        is InsertLdu -> """
                        insert into local_delivery_unit2(local_delivery_unit_id, probation_area_code, local_delivery_unit_code, create_date_time, create_user_id)
                        values ( md5(random()::text || clock_timestamp()::text)::uuid, '${instruction.ldu.probationAreaCode}', '${instruction.ldu.localDeliveryUnitCode}', now(), 'dev');
                        """.trimIndent()

        is InsertLduWithFmb -> """
                        insert into local_delivery_unit2(local_delivery_unit_id, probation_area_code, local_delivery_unit_code, functional_mailbox, create_date_time, create_user_id)
                        values ( md5(random()::text || clock_timestamp()::text)::uuid, '${instruction.ldu.probationAreaCode}', '${instruction.ldu.localDeliveryUnitCode}', '${instruction.functionalMailbox}', now(), 'dev');
                        """.trimIndent()

        is InsertTeamWithFmb -> """
                        insert into probation_team (local_delivery_unit_id, team_code, functional_mailbox)
                        values (
                           ( select local_delivery_unit_id
                               from local_delivery_unit2
                              where probation_area_code = '${instruction.ldu.probationAreaCode}' and
                                    local_delivery_unit_code = '${instruction.ldu.localDeliveryUnitCode}'),
                           '${instruction.team.teamCode}',
                           '${instruction.team.functionalMailbox.toLowerCase()}'
                        );
                        """.trimIndent()
        else -> ""
    }

    private fun readSheetRows(input: InputStream, sheetName: String): Sequence<MailboxSpec> = WorkbookFactory
            .create(input)
            .getSheet(sheetName)
            .rowIterator()
            .asSequence()
            .drop(1)
            .takeWhile { it.getCell(0)?.stringCellValue?.isNotEmpty() ?: false }
            .map(::toMailboxSpec)

    private fun toMailboxSpec(row: Row): MailboxSpec = MailboxSpec(
            row.text(1),
            row.text(4),
            row.text(8),
            row.text(10))

    private fun Row.text(index: Int) = this.getCell(index).stringCellValue.trim()
}

fun main() {
    val teamsByLdu: Map<Ldu, List<Team>> = teamsByLdu(readSheets(fileName, sheetNames))

    val instructions: List<Instruction> = teamsByLdu
            .asIterable()
            .flatMap { (ldu, teams) -> instructionsForLdu(ldu, teams) }

    val sqls: List<String> = instructions.map(::instructionToSql)

    File("build/functionalMailboxes.sql")
            .bufferedWriter()
            .use { writer ->
                sqls.forEach {
                    writer.write(it)
                    writer.write("\n\n")
                }
            }
}