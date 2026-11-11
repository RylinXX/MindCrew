package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.service.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MindCrewChatControllerTest {

    @Test
    void streamShouldMapInvalidKbIdsToParamError() {
        UserService userService = Mockito.mock(UserService.class);
        MindCrewChatController controller = new MindCrewChatController(
                null,
                userService,
                null,
                null,
                null
        );

        BusinessException exception = assertThrows(BusinessException.class, () ->
                controller.stream(null, "hello", "1,abc,2", null));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), exception.getCode());
    }
}
