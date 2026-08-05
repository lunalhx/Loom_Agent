package cn.lunalhx.ai;

import cn.lunalhx.ai.cli.CliMain;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        int exitCode = CliMain.run(args);
        System.exit(exitCode);
    }

}
