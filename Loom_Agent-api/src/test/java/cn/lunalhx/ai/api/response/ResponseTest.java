package cn.lunalhx.ai.api.response;

import cn.lunalhx.ai.types.enums.ResponseCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseTest {

    @Test
    void successShouldUseDefaultCodeAndInfo() {
        Response<String> resp = Response.success("hello");

        assertThat(resp.getCode()).isEqualTo(ResponseCode.SUCCESS.getCode());
        assertThat(resp.getInfo()).isEqualTo(ResponseCode.SUCCESS.getInfo());
        assertThat(resp.getData()).isEqualTo("hello");
    }

    @Test
    void successShouldAcceptCustomInfo() {
        Response<String> resp = Response.success("hello", "custom message");

        assertThat(resp.getCode()).isEqualTo(ResponseCode.SUCCESS.getCode());
        assertThat(resp.getInfo()).isEqualTo("custom message");
        assertThat(resp.getData()).isEqualTo("hello");
    }

    @Test
    void successShouldAllowNullData() {
        Response<Void> resp = Response.success(null);

        assertThat(resp.getCode()).isEqualTo(ResponseCode.SUCCESS.getCode());
        assertThat(resp.getInfo()).isEqualTo(ResponseCode.SUCCESS.getInfo());
        assertThat(resp.getData()).isNull();
    }
}
