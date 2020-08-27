package io.atlassian.earl

import io.atlassian.earl.styles.Styles
import io.atlassian.earl.views.MainView
import javafx.application.Application
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.ConfigurableApplicationContext
import tornadofx.*
import kotlin.reflect.KClass

@SpringBootApplication
class DynamoDbSizerApplication : App(MainView::class, Styles::class) {
    private lateinit var context: ConfigurableApplicationContext

    override fun init() {
        context = SpringApplication.run(this.javaClass)



        context.autowireCapableBeanFactory.autowireBean(this)
        FX.dicontainer = object : DIContainer {
            override fun <T : Any> getInstance(type: KClass<T>): T = context.getBean(type.java)
        }
        reloadStylesheetsOnFocus()
    }

    override fun stop() {
        super.stop()
        context.close()
    }
}

fun main(args: Array<String>) {
    Application.launch(DynamoDbSizerApplication::class.java, *args)
}
