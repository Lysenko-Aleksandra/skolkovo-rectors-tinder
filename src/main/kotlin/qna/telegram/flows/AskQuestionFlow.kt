package qna.telegram.flows

import auth.domain.entities.User
import com.ithersta.tgbotapi.fsm.StatefulContext
import com.ithersta.tgbotapi.fsm.builders.RoleFilterBuilder
import com.ithersta.tgbotapi.fsm.entities.triggers.onEnter
import com.ithersta.tgbotapi.fsm.entities.triggers.onText
import common.telegram.DialogState
import common.telegram.functions.chooseQuestionAreas
import common.telegram.functions.confirmationInlineKeyboard
import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.delete
import dev.inmo.tgbotapi.extensions.api.edit.edit
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.send.sendContact
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.utils.messageCallbackQueryOrNull
import dev.inmo.tgbotapi.extensions.utils.types.buttons.replyKeyboard
import dev.inmo.tgbotapi.extensions.utils.types.buttons.simpleButton
import dev.inmo.tgbotapi.extensions.utils.withContentOrNull
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.UserId
import dev.inmo.tgbotapi.types.buttons.ReplyKeyboardRemove
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.toChatId
import dev.inmo.tgbotapi.utils.row
import generated.onDataCallbackQuery
import kotlinx.coroutines.launch
import menus.states.MenuState
import org.koin.core.component.inject
import qna.domain.entities.Question
import qna.domain.entities.QuestionIntent
import qna.domain.usecases.*
import qna.telegram.queries.AcceptQuestionQuery
import qna.telegram.queries.AcceptUserQuery
import qna.telegram.queries.DeclineQuestionQuery
import qna.telegram.queries.DeclineUserQuery
import qna.telegram.states.AskFullQuestion
import qna.telegram.states.ChooseQuestionAreas
import qna.telegram.states.ChooseQuestionIntent
import qna.telegram.states.SendQuestionToCommunity
import qna.telegram.strings.ButtonStrings
import qna.telegram.strings.Strings

fun RoleFilterBuilder<DialogState, User, User.Normal, UserId>.askQuestionFlow() {
    val getUsersByAreaUseCase: GetUsersByAreaUseCase by inject()
    val addQuestionUseCase: AddQuestionUseCase by inject()
    val getUserDetailsUseCase: GetUserDetailsUseCase by inject()
    val getQuestionByIdUseCase: GetQuestionByIdUseCase by inject()
    val addResponseUseCase: AddResponseUseCase by inject()
    val addAcceptedResponseRepoUseCase: AddAcceptedResponseRepoUseCase by inject()
    state<MenuState.Questions.AskQuestion> {
        onEnter {
            sendTextMessage(
                it,
                Strings.Question.SubjectQuestion,
                replyMarkup = ReplyKeyboardRemove()
            )
        }
        onText { message ->
            state.override { AskFullQuestion(message.content.text) }
        }
    }
    state<AskFullQuestion> {
        onEnter {
            sendTextMessage(it, Strings.Question.WordingQuestion)
        }
        onText { message ->
            state.override { ChooseQuestionAreas(subject, message.content.text, emptySet()) }
        }
    }
    state<ChooseQuestionAreas> {
        chooseQuestionAreas(
            text = auth.telegram.Strings.Question.ChooseQuestionArea,
            getAreas = { it.areas },
            getMessageId = { it.messageId },
            onSelectionChanged = { state, areas -> state.copy(areas = areas) },
            onMessageIdSet = { state, messageId -> state.copy(messageId = messageId) },
            onFinish = { ChooseQuestionIntent(it.subject, it.question, it.areas) }
        )
    }
    state<ChooseQuestionIntent> {
        onEnter {
            sendTextMessage(
                it,
                Strings.Question.AskingQuestionIntent,
                replyMarkup = replyKeyboard {
                    ButtonStrings.Question.questionIntentToString.forEach {
                        row {
                            simpleButton(it.value)
                        }
                    }
                }
            )
        }
        onText { message ->
            val intent = ButtonStrings.Question.stringToQuestionIntent[message.content.text]
            if (intent != null) {
                if (intent == QuestionIntent.QuestionToColleagues) {
                    // TODO тут нужен новый state новый для отправки вопроса в сообщество(в тг канал)
                    state.override { SendQuestionToCommunity(subject, question, areas, intent) }
                } else {
                    state.override { SendQuestionToCommunity(subject, question, areas, intent) }
                }
            } else {
                sendTextMessage(message.chat, Strings.Question.InvalidQuestionIntent)
                state.override { ChooseQuestionIntent(subject, question, areas) }
            }
        }
    }
    state<SendQuestionToCommunity> {
        onEnter {
            sendTextMessage(
                it,
                Strings.Question.CompletedQuestion,
                replyMarkup = replyKeyboard {
                    row {
                        simpleButton(ButtonStrings.SendQuestion)
                    }
                }
            )
        }
        onText(ButtonStrings.SendQuestion) { message ->
            val question = addQuestionUseCase(
                authorId = message.chat.id.chatId,
                state.snapshot.intent,
                state.snapshot.subject,
                state.snapshot.question,
                state.snapshot.areas
            )
            sendTextMessage(
                message.chat,
                Strings.Question.Success
            )
            coroutineScope.launch {
                state.snapshot.areas.forEach {
                    val listOfValidUsers: List<Long> =
                        getUsersByAreaUseCase(
                            it,
                            userId = message.chat.id.chatId
                        )
                    listOfValidUsers.forEach {
                        runCatching {
                            sendQuestionMessage(it.toChatId(), question)
                        }
                    }
                }
            }
            state.override { DialogState.Empty }
        }
    }
    anyState {
        onDataCallbackQuery(DeclineQuestionQuery::class) { (_, query) ->
            val message = query.messageCallbackQueryOrNull()?.message ?: return@onDataCallbackQuery
            delete(message)
            answer(query)
        }
        onDataCallbackQuery(AcceptQuestionQuery::class) { (data, query) ->
            val question = getQuestionByIdUseCase(data.questionId)
            val message = query.messageCallbackQueryOrNull()?.message?.withContentOrNull<TextContent>()
            message?.let {
                edit(
                    it,
                    entities = Strings.ToAnswerUser.editMessage(question!!.subject, question.text),
                    replyMarkup = null
                )
            }
            addResponseUseCase(data.questionId, query.user.id.chatId)
            sendTextMessage(
                query.user.id,
                Strings.ToAnswerUser.SentAgreement
            )
            answer(query)
        }
        onDataCallbackQuery(DeclineUserQuery::class) { (data, query) ->
            val message = query.messageCallbackQueryOrNull()?.message
            message?.let { delete(it) }
            sendTextMessage(
                data.userId.toChatId(),
                Strings.ToAnswerUser.QuestionResolved
            )
            answer(query)
        }
        onDataCallbackQuery(AcceptUserQuery::class) { (data, query) ->
            val message = query.messageCallbackQueryOrNull()?.message
            message?.let { editMessageReplyMarkup(it, null) }
            val respondent = getUserDetailsUseCase(data.respondentId)
            checkNotNull(respondent)
            val question = getQuestionByIdUseCase(data.questionId)
            addAcceptedResponseRepoUseCase(data.responseId)
            sendContact(
                query.user,
                phoneNumber = respondent.phoneNumber.value,
                firstName = respondent.name,
            )
            sendTextMessage(
                data.respondentId.toChatId(),
                Strings.ToAnswerUser.waitingForCompanion(question!!.subject)
            )
            sendTextMessage(
                query.user.id,
                Strings.ToAskUser.WriteToCompanion
            )
            sendTextMessage(
                query.user.id,
                question.text
            )
            sendTextMessage(
                query.user.id,
                Strings.ToAskUser.CopyQuestion
            )
            answer(query)
        }
    }
}

suspend fun StatefulContext<DialogState, User, SendQuestionToCommunity, User.Normal>.sendQuestionMessage(
    chatId: ChatId,
    question: Question
) = sendTextMessage(
    chatId,
    Strings.ToAnswerUser.message(question.subject, question.text),
    replyMarkup = confirmationInlineKeyboard(
        positiveData = AcceptQuestionQuery(question.id!!),
        negativeData = DeclineQuestionQuery
    )
)